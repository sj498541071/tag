package org.etocrm.tagManager.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.etocrm.core.enums.BusinessEnum;
import org.etocrm.core.util.BeanUtils;
import org.etocrm.core.util.JsonUtil;
import org.etocrm.core.util.ResponseVO;
import org.etocrm.dynamicDataSource.service.IDynamicService;
import org.etocrm.dynamicDataSource.util.RedisUtil;
import org.etocrm.tagManager.api.IDataManagerService;
import org.etocrm.tagManager.batch.impl.common.BatchCommonService;
import org.etocrm.tagManager.constant.TagConstant;
import org.etocrm.tagManager.enums.TagDictEnum;
import org.etocrm.tagManager.mapper.ISysModelTableColumnMapper;
import org.etocrm.tagManager.mapper.ISysTagGroupMapper;
import org.etocrm.tagManager.mapper.ISysTagGroupUserMapper;
import org.etocrm.tagManager.model.DO.SysLifeCycleModelUserPO;
import org.etocrm.tagManager.model.DO.SysModelTableColumnDO;
import org.etocrm.tagManager.model.DO.SysTagGroupDO;
import org.etocrm.tagManager.model.DO.SysTagGroupUserDO;
import org.etocrm.tagManager.model.VO.DictFindAllVO;
import org.etocrm.tagManager.model.VO.LogicalOperationsChildVO;
import org.etocrm.tagManager.model.VO.LogicalOperationsVO;
import org.etocrm.tagManager.model.VO.ModelTable.TagSysModelTableColumnVO;
import org.etocrm.tagManager.model.VO.SysDictVO;
import org.etocrm.tagManager.model.VO.tagGroup.SplitWayVO;
import org.etocrm.tagManager.model.VO.tagGroup.SysTagGroupSonUserInfo;
import org.etocrm.tagManager.model.VO.tagGroup.SysTagGroupUserSplitDetailVO;
import org.etocrm.tagManager.service.AsyncServiceManager;
import org.etocrm.tagManager.util.DataRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Create By peter.li
 */
@Slf4j
@Service
public class AsyncServiceManagerImpl implements AsyncServiceManager {

    @Autowired
    private ISysTagGroupUserMapper sysTagGroupUserMapper;

    @Autowired
    private ISysTagGroupMapper sysTagGroupMapper;

    @Autowired
    private IDynamicService dynamicService;

    @Autowired
    private ISysModelTableColumnMapper sysModelTableColumnMapper;

    @Autowired
    private IDataManagerService dataManagerService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private BatchCommonService batchCommonService;

    @Async//@Async("asyncServiceExecutorTag")
    public void asyncSplitTagGroupUsersDetail(SysTagGroupUserSplitDetailVO splitVO/*,Long dataSourceId*/) {
        try {
            splitTagGroupUsersDetail(splitVO/*,dataSourceId*/);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    @Transactional(rollbackFor = {Exception.class})
    public void splitTagGroupUsersDetail(SysTagGroupUserSplitDetailVO splitVO/*, Long dataSourceId*/) throws Exception {

        //   DynamicDataSource.setDataSource(dataSourceId);

        //??????tagGroupId????????????????????????  ???????????????order by rand()
        List<Long> splitDOS = getTagGroupUsersByRand(splitVO/*, dataSourceId*/);

        //   DynamicDataSource.setDataSource(dataSourceId);
        //"tagGroupId":2,
        //"splitWay":"avg" ??? "splitWay":"set"
        //??????id
        //   Long tagGroupId = splitVO.getTagGroupId();
        //????????????????????????
        String splitWay = splitVO.getSplitWay();
        //??????????????????????????????????????????????????????
        //avg: "data":[{"count":12}]  ?????????
        //set:"data":[{"subcontractNo":"A","count":10},{"subcontractNo":"B","count":20}...]
        List<SplitWayVO> dataList = splitVO.getData();

        //??????26?????????????????????(A-Z)
        List<String> enList = new LinkedList<>();
        for (int i = 0; i < 26; i++) {
            enList.add(String.valueOf((char) ('A' + i)));
        }

        Boolean saveResult = true;
//        Integer childGroupCount = 0;//???????????????

        //  ?????????set ???????????????
        List<SysTagGroupSonUserInfo> sonUserInfoList = new ArrayList<>();
        if (splitWay.equals("avg")) {//??????????????????????????????

            sonUserInfoList = splitTagGroupUsersDetailByAvg(splitVO, splitDOS, enList);

        } else if (splitWay.equals("set")) {//??????????????????????????????

            sonUserInfoList = splitTagGroupUsersDetailBySet(splitVO, splitDOS);

        } else {//????????????
            saveResult = false;
        }

        if (saveResult) {
            //???????????????????????????????????????
            String splitRule = JSON.toJSONString(splitVO);
            SysTagGroupDO groupDO = new SysTagGroupDO();
            groupDO.setId(splitVO.getTagGroupId());
            groupDO.setTagGroupSplitCount(Long.valueOf(sonUserInfoList.size()));
            groupDO.setSonCountInfo(JSON.toJSONString(sonUserInfoList));
            groupDO.setTagGroupSplitRule(splitRule);
            sysTagGroupMapper.updateById(groupDO);
        }
        log.info("----------------------??????????????????--------------------???????????????" + sonUserInfoList.size() + "???");
    }

    //??????????????????????????????
    private List<SysTagGroupSonUserInfo> splitTagGroupUsersDetailByAvg(SysTagGroupUserSplitDetailVO splitVO, List<Long> splitDOS,
                                                                       List<String> enList) throws Exception {
        List<SysTagGroupSonUserInfo> sonUserInfoList = new ArrayList<>();

        Integer num = 0;
        int count = 1;
        //??????id
        Long tagGroupId = splitVO.getTagGroupId();
        //??????????????????????????????????????????????????????
        //avg: "data":[{"count":12}]  ?????????
        //set:"data":[{"subcontractNo":"A","count":10},{"subcontractNo":"B","count":20}...]
        List<SplitWayVO> dataList = splitVO.getData();
        //??????????????????????????????
        SplitWayVO splitWayVO = dataList.get(0);
        count = Integer.valueOf(splitWayVO.getCount()) == null ? count : splitWayVO.getCount();//?????????
        if (count > 26) {
            count = 26;
        } else if (count < 1) {
            count = 1;
        }

        //????????????????????????
        List<List<Long>> result = averageAssign(splitDOS, count);
        String subcontractNo;
        SysTagGroupSonUserInfo sonUserInfo;
        int index = 0;
        for (List<Long> list : result) {
            subcontractNo = enList.get(index);
            num = isSplitBySize(subcontractNo, list, tagGroupId, num);

            index++;

            sonUserInfo = new SysTagGroupSonUserInfo();
            sonUserInfo.setName(subcontractNo);
            sonUserInfo.setCount(list.size());
            sonUserInfoList.add(sonUserInfo);
        }

        log.info("----------------???????????????--------------????????? ??????" + num + "???");
        return sonUserInfoList;
    }

    //??????????????????????????????
    private List<SysTagGroupSonUserInfo> splitTagGroupUsersDetailBySet(SysTagGroupUserSplitDetailVO splitVO, List<Long> splitDOS) throws Exception {
        List<SysTagGroupSonUserInfo> sonUserInfoList = new ArrayList<>();
        Integer num = 0;
        int count = 1;
        Boolean isOut = true;
        //??????id
        Long tagGroupId = splitVO.getTagGroupId();
        //??????????????????????????????????????????????????????
        //avg: "data":[{"count":12}]  ?????????
        //set:"data":[{"subcontractNo":"A","count":10},{"subcontractNo":"B","count":20}...]
        List<SplitWayVO> dataList = splitVO.getData();
        //??????????????????????????????
        SysTagGroupSonUserInfo sonUserInfo;
        int sum = 0;
        for (int i = 0; i < dataList.size(); i++) {
            SplitWayVO splitWayVO = dataList.get(i);
            count = splitWayVO.getCount();//??????????????????
            if (sum + count <= splitDOS.size()) {//????????????count??????????????????splitDOS.size()
                String subcontractNo = splitWayVO.getSubcontractNo();
                List<Long> childList = splitDOS.subList(sum, sum + count);

                num = isSplitBySize(subcontractNo, childList, tagGroupId, num);

                sum = sum + count;

                sonUserInfo = new SysTagGroupSonUserInfo();
                sonUserInfo.setName(subcontractNo);
                sonUserInfo.setCount(childList.size());
                sonUserInfoList.add(sonUserInfo);

            } else if (sum + count > splitDOS.size() && sum < splitDOS.size()) {//????????????count??????????????????
                String subcontractNo = splitWayVO.getSubcontractNo();
                List<Long> childList = splitDOS.subList(sum, splitDOS.size());

                num = isSplitBySize(subcontractNo, childList, tagGroupId, num);

                isOut = false;//???????????????????????????

                sonUserInfo = new SysTagGroupSonUserInfo();
                sonUserInfo.setName(subcontractNo);
                sonUserInfo.setCount(childList.size());
                sonUserInfoList.add(sonUserInfo);

                break;
            }
        }

        //??????????????????????????????,isOut???true??????????????????count??????????????????
        if (isOut) {
            String subcontractNo = "ZZZ";
            List<Long> childList = splitDOS.subList(sum, splitDOS.size());
            num = isSplitBySize(subcontractNo, childList, tagGroupId, num);

            sonUserInfo = new SysTagGroupSonUserInfo();
            sonUserInfo.setName(subcontractNo);
            sonUserInfo.setCount(childList.size());
            sonUserInfoList.add(sonUserInfo);
        }

        log.info("----------------???????????????--------------????????? ??????" + num + "???");
        return sonUserInfoList;
    }

    //????????????????????????????????????????????????????????????
    private Integer isSplitBySize(String subcontractNo, List<Long> childList, Long tagGroupId, Integer num) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (Long id : childList) {
            ids.add(id);
        }
        if (ids.size() > 20000) {//??????in??????????????????????????????????????????
            log.info("----------------??????????????????20000???????????????-----------------");
            int n = ids.size() / 20000;
            if (ids.size() % 20000 != 0) {
                n = n + 1;
            }
            List<List<Long>> ids01 = averageAssign(ids, n);
            for (int j = 0; j < ids01.size(); j++) {

                banchUpdateTagGroupUser(subcontractNo, tagGroupId, ids01.get(j));
                log.info("----------------?????????" + j + "????????????" + ids01.get(j).size() + "-------------------");
            }
        } else {
            if (ids.size() > 0) {
                banchUpdateTagGroupUser(subcontractNo, tagGroupId, ids);
            }
        }
        log.info("----------------???????????????------------?????????" + subcontractNo + "???????????? ??????" + ids.size() + "???");
        num = num + ids.size();
        return num;
    }

    //????????????
    private void banchUpdateTagGroupUser(String subcontractNo, Long tagGroupId, List<Long> ids) throws Exception {
        SysTagGroupUserDO splitDO = new SysTagGroupUserDO();
        splitDO.setSubcontractNo(subcontractNo);
        UpdateWrapper update = new UpdateWrapper();
        update.eq("tag_group_id", tagGroupId);
        update.in("id", ids);
        sysTagGroupUserMapper.update(splitDO, update);
    }

    //???????????????list????????????
    private <T> List<List<T>> averageAssign(List<T> data, int count) throws Exception {
        List<List<T>> result = new LinkedList<>();
        int num = data.size() / count;//??????????????????
        for (int i = 0; i < count; i++) {
            List<T> list = new ArrayList<>();
            if (i < count - 1) {
                list = data.subList(i * num, (i + 1) * num);
            } else {//?????????????????????????????????????????????????????????????????????????????????
                list = data.subList(i * num, data.size());
            }
            result.add(list);
        }
        return result;
    }


    //??????tagGroupId????????????????????????  ???????????????order by rand()
    private List<Long> getTagGroupUsersByRand(SysTagGroupUserSplitDetailVO splitVO/*,Long dataSourceId*/) throws Exception {
        List<String> tableNames = new ArrayList<String>();
        tableNames.add("sys_tag_group_user t");
        List<String> columns = new ArrayList<>();
        columns.add("t.id id");
        String whereClause = "t.tag_group_id = " + splitVO.getTagGroupId();
        //    DynamicDataSource.setDataSource(dataSourceId);
        List<Long> idsList = dynamicService.getIdsList(tableNames, columns, whereClause, "rand()");//rand()??????????????????

        int arrLength = getListLenght(idsList);//??????????????????List?????????????????????
        log.info("---------------idsList???length??????" + arrLength + "," + arrLength / (1024 * 1024) + "???-----------------");
        return idsList;
    }

    private int getListLenght(List<Long> idsList) throws NoSuchFieldException, IllegalAccessException {
        Field f = ArrayList.class.getDeclaredField("elementData");
        f.setAccessible(true);
        Object[] elementData = (Object[]) f.get(idsList);

        return elementData.length;
    }

    /**
     * ??????????????????????????????
     *
     * @param modelId
     * @param modelRuleId
     * @param userIdList
     */
    @Override
    @Async
    public void asyncBatchSaveLifeCycleUser(Long modelId, Long modelRuleId, List<Long> userIdList) {
        // ??????userId ???????????????????????????
        List<SysLifeCycleModelUserPO> userPOList = new ArrayList<>(userIdList.size());
        SysLifeCycleModelUserPO userPO;
        for (Long userId : userIdList) {
            userPO = new SysLifeCycleModelUserPO();
            userPO.setModelId(modelId);
            userPO.setModelRuleId(modelRuleId);
            userPO.setUserId(userId);
            userPOList.add(userPO);
        }

        List<HashMap<String, Object>> hashMaps = JsonUtil.JsonToMapList(JsonUtil.toJson(userPOList));
        Set<String> strings = hashMaps.get(0).keySet();
        String column = humpToLine2(StringUtils.join(strings, ","));
        List<String> columns = Arrays.asList(column.split(","));
        TableName table = SysLifeCycleModelUserPO.class.getAnnotation(TableName.class);
        String tableName = "";
        if (table != null) {
            tableName = table.value();
        }
        dynamicService.insertPlusRecord(tableName, columns, hashMaps, null);
    }

    /**
     * ?????????????????? set redis
     *
     * @param modelTableId
     */
    @Override
    @Async
    public void asyncSetModelColumnInfoRedis(Long modelTableId) {
        //???????????????
        redisUtil.deleteCache(TagConstant.MODEL_TABLE_COLUMN_INFO_REDIS_PREFIX+modelTableId);
        // set ??????
        this.getModelColumnByTableIdFromDB(modelTableId);
    }

    @Override
    public ResponseVO<List<TagSysModelTableColumnVO>> getModelColumnByTableIdFromDB(Long id) {
        List<SysModelTableColumnDO> sysModelTableColumnDOList = this.getShowListById(id);
        if (CollectionUtil.isEmpty(sysModelTableColumnDOList)) {
            return ResponseVO.success(new ArrayList<>());
        }
        //??????dataType ??????????????????
        Map<Long, SysDictVO> valueTypeDictMap = getMapByDictParentCode(TagDictEnum.DATE_TYPE.getCode());
        if (null == valueTypeDictMap) {
            return ResponseVO.errorParams("???????????????????????????????????????");
        }
        //????????????????????????????????????
        Map<Long, SysDictVO> operatorDictMap = getMapByDictParentCode(TagDictEnum.ALL_OPERATERS.getCode());
        if (null == operatorDictMap) {
            return ResponseVO.errorParams("???????????????????????????????????????");
        }
        List<TagSysModelTableColumnVO> list = new ArrayList<>();
        for (SysModelTableColumnDO modelTableColumnDO : sysModelTableColumnDOList) {
            TagSysModelTableColumnVO sysModelTableColumnVO = new TagSysModelTableColumnVO();
            BeanUtils.copyPropertiesIgnoreNull(modelTableColumnDO, sysModelTableColumnVO);

            sysModelTableColumnVO = this.setValueTypeInfo(sysModelTableColumnVO, modelTableColumnDO.getDataRange(), valueTypeDictMap.get(modelTableColumnDO.getValueType()));

            List<LogicalOperationsVO> logicOperatorList = this.getLogicOperatorList(modelTableColumnDO.getLogicalOperations(), operatorDictMap);
            if (null == logicOperatorList) {
                return ResponseVO.errorParams("????????????????????????????????????????????????");
            }
            sysModelTableColumnVO.setLogicalOperations(logicOperatorList);

            list.add(sysModelTableColumnVO);
        }

        // set redis
        redisUtil.set(TagConstant.MODEL_TABLE_COLUMN_INFO_REDIS_PREFIX + id, JSON.toJSONString(list));
        return ResponseVO.success(list);
    }

    /**
     * ????????????id ???????????????????????????list
     */
    private List<SysModelTableColumnDO> getShowListById(Long id) {
        SysModelTableColumnDO sysModelTableColumnDO = new SysModelTableColumnDO();
        sysModelTableColumnDO.setModelTableId(id);
        sysModelTableColumnDO.setDelFlag(Long.valueOf(BusinessEnum.SHOW.getCode()));
        return sysModelTableColumnMapper.selectList(new LambdaQueryWrapper<>(sysModelTableColumnDO));
    }

    /**
     * ??????dictParentCode ??????????????????
     */
    private Map<Long, SysDictVO> getMapByDictParentCode(String dictParentCode) {
        List<SysDictVO> dictList = this.getListByDictParentCode(dictParentCode);
        if (null != dictList) {
            Map<Long, SysDictVO> resultMap = new HashMap<>();
            dictList.stream().forEach(dict -> {
                resultMap.put(dict.getId(), dict);
            });
            return resultMap;
        }
        return null;
    }

    private List<SysDictVO> getListByDictParentCode(String dictParentCode) {
        DictFindAllVO vo = new DictFindAllVO();
        vo.setDictParentCode(dictParentCode);
        ResponseVO<List<SysDictVO>> dictList = dataManagerService.findAll(vo);
        if (dictList.getCode() != 0 || CollectionUtil.isEmpty(dictList.getData())) {
            return null;
        }
        return dictList.getData();
    }

    private List<LogicalOperationsChildVO> getChildOperatorByDictCode(String dictCode) {
        List<LogicalOperationsChildVO> result = new ArrayList<>();// ????????????????????????????????????null
        if (dictCode.equals(TagDictEnum.TAG_DICT_AVG.getCode()) || dictCode.equals(TagDictEnum.TAG_DICT_COUNT.getCode()) ||
                dictCode.equals(TagDictEnum.TAG_DICT_DISTINCT_COUNT.getCode()) || dictCode.equals(TagDictEnum.TAG_DICT_SUM.getCode())) {

            List<SysDictVO> data = this.getListByDictParentCode(dictCode);
            if (null == data) {
                return null;
            }
            result = new ArrayList<>();
            LogicalOperationsChildVO logicalOperationsChildVO;
            for (SysDictVO datum : data) {
                logicalOperationsChildVO = new LogicalOperationsChildVO();
                logicalOperationsChildVO.setId(datum.getId());
                logicalOperationsChildVO.setDictCode(datum.getDictCode());
                logicalOperationsChildVO.setName(datum.getDictName());
                result.add(logicalOperationsChildVO);
            }
        }
        return result;
    }

    private TagSysModelTableColumnVO setValueTypeInfo(TagSysModelTableColumnVO sysModelTableColumnVO, String dataRange, SysDictVO dictVO) {
        if (null == dictVO) {
            dictVO = new SysDictVO();
        }
        sysModelTableColumnVO.setValueTypeName(dictVO.getDictName());
        sysModelTableColumnVO.setValueType(dictVO.getId());
        sysModelTableColumnVO.setValueTypeDictCode(dictVO.getDictCode());
        if (StrUtil.equals(TagDictEnum.TAG_ENUMERATION.getCode(), dictVO.getDictCode())) {
            sysModelTableColumnVO.setDataRange(JSONArray.parseArray(dataRange, DataRange.class));
        } else {
            sysModelTableColumnVO.setDataRange(new ArrayList<>());
        }

        return sysModelTableColumnVO;
    }


    private List<LogicalOperationsVO> getLogicOperatorList(String logicalOperations, Map<Long, SysDictVO> operatorDictMap) {
        List<LogicalOperationsVO> logicalOperationsVOS = JSONArray.parseArray(logicalOperations, LogicalOperationsVO.class);
        for (LogicalOperationsVO logicalOperationsVO : logicalOperationsVOS) {

            SysDictVO logicalOperationDict = operatorDictMap.get(logicalOperationsVO.getId());
            if (null == logicalOperationDict) {
                return null;
            }

            String dictCode = logicalOperationDict.getDictCode();
            List<LogicalOperationsChildVO> childOperatorList = this.getChildOperatorByDictCode(dictCode);
            if (null == childOperatorList) {
                return null;
            }
            logicalOperationsVO.setChild(childOperatorList);
            logicalOperationsVO.setDictCode(dictCode);
        }
        return logicalOperationsVOS;
    }


    public static String humpToLine2(String str) {
        Pattern humpPattern = Pattern.compile("[A-Z]");
        Matcher matcher = humpPattern.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "_" + matcher.group(0).toLowerCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }


    @Override
    @Async
    public void asyncDeleteTag(Long tagId,String tagType){
        batchCommonService.deleteTag(tagId,tagType);
    }

}
