/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.common.statictopic;

import com.google.common.collect.ImmutableList;
import org.apache.rocketmq.common.MixAll;

import java.io.File;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class TopicQueueMappingUtils {

    public static class MappingAllocator {
        Map<String, Integer> brokerNumMap = new HashMap<String, Integer>();
        Map<Integer, String> idToBroker = new HashMap<Integer, String>();
        int currentIndex = 0;
        Random random = new Random();
        List<String> leastBrokers = new ArrayList<String>();
        private MappingAllocator(Map<Integer, String> idToBroker, Map<String, Integer> brokerNumMap) {
            this.idToBroker.putAll(idToBroker);
            this.brokerNumMap.putAll(brokerNumMap);
        }

        private void freshState() {
            int minNum = -1;
            for (Map.Entry<String, Integer> entry : brokerNumMap.entrySet()) {
                if (entry.getValue() > minNum) {
                    leastBrokers.clear();
                    leastBrokers.add(entry.getKey());
                } else if (entry.getValue() == minNum) {
                    leastBrokers.add(entry.getKey());
                }
            }
            currentIndex = random.nextInt(leastBrokers.size());
        }
        private String nextBroker() {
            if (leastBrokers.isEmpty()) {
                freshState();
            }
            int tmpIndex = currentIndex % leastBrokers.size();
            return leastBrokers.remove(tmpIndex);
        }

        public Map<String, Integer> getBrokerNumMap() {
            return brokerNumMap;
        }

        public void upToNum(int maxQueueNum) {
            int currSize = idToBroker.size();
            if (maxQueueNum <= currSize) {
                return;
            }
            for (int i = currSize; i < maxQueueNum; i++) {
                String nextBroker = nextBroker();
                if (brokerNumMap.containsKey(nextBroker)) {
                    brokerNumMap.put(nextBroker, brokerNumMap.get(nextBroker) + 1);
                } else {
                    brokerNumMap.put(nextBroker, 1);
                }
                idToBroker.put(i, nextBroker);
            }
        }

        public Map<Integer, String> getIdToBroker() {
            return idToBroker;
        }
    }

    public static MappingAllocator buildMappingAllocator(Map<Integer, String> idToBroker, Map<String, Integer> brokerNumMap) {
        return new MappingAllocator(idToBroker, brokerNumMap);
    }

    public static Map.Entry<Long, Integer> findMaxEpochAndQueueNum(List<TopicQueueMappingDetail> mappingDetailList) {
        long epoch = -1;
        int queueNum = 0;
        for (TopicQueueMappingDetail mappingDetail : mappingDetailList) {
            if (mappingDetail.getEpoch() > epoch) {
                epoch = mappingDetail.getEpoch();
            }
            if (mappingDetail.getTotalQueues() > queueNum) {
                queueNum = mappingDetail.getTotalQueues();
            }
        }
        return new AbstractMap.SimpleImmutableEntry<Long, Integer>(epoch, queueNum);
    }

    public static List<TopicQueueMappingDetail> getMappingDetailFromConfig(Collection<TopicConfigAndQueueMapping> configs) {
        List<TopicQueueMappingDetail> detailList = new ArrayList<TopicQueueMappingDetail>();
        for (TopicConfigAndQueueMapping configMapping : configs) {
            if (configMapping.getMappingDetail() != null) {
                detailList.add(configMapping.getMappingDetail());
            }
        }
        return detailList;
    }

    public static Map.Entry<Long, Integer> validConsistenceOfTopicConfigAndQueueMapping(Map<String, TopicConfigAndQueueMapping> brokerConfigMap) {
        if (brokerConfigMap == null
            || brokerConfigMap.isEmpty()) {
            return null;
        }
        //make sure it it not null
        String topic = null;
        long maxEpoch = -1;
        int maxNum = -1;
        for (Map.Entry<String, TopicConfigAndQueueMapping> entry : brokerConfigMap.entrySet()) {
            String broker = entry.getKey();
            TopicConfigAndQueueMapping configMapping = entry.getValue();
            if (configMapping.getMappingDetail() == null) {
                throw new RuntimeException("Mapping info should not be null in broker " + broker);
            }
            TopicQueueMappingDetail mappingDetail = configMapping.getMappingDetail();
            if (!broker.equals(mappingDetail.getBname())) {
                throw new RuntimeException(String.format("The broker name is not equal %s != %s ", broker, mappingDetail.getBname()));
            }
            if (mappingDetail.isDirty()) {
                throw new RuntimeException("The mapping info is dirty in broker  " + broker);
            }
            if (!configMapping.getTopicName().equals(mappingDetail.getTopic())) {
                throw new RuntimeException("The topic name is inconsistent in broker  " + broker);
            }
            if (topic != null
                && !topic.equals(mappingDetail.getTopic())) {
                throw new RuntimeException("The topic name is inconsistent in broker  " + broker);
            } else {
                topic = mappingDetail.getTopic();
            }

            if (maxEpoch != -1
                && maxEpoch != mappingDetail.getEpoch()) {
                throw new RuntimeException(String.format("epoch dose not match %d != %d in %s", maxEpoch, mappingDetail.getEpoch(), mappingDetail.getBname()));
            } else {
                maxEpoch = mappingDetail.getEpoch();
            }

            if (maxNum != -1
                && maxNum != mappingDetail.getTotalQueues()) {
                throw new RuntimeException(String.format("total queue number dose not match %d != %d in %s", maxNum, mappingDetail.getTotalQueues(), mappingDetail.getBname()));
            } else {
                maxNum = mappingDetail.getTotalQueues();
            }
        }
        return new AbstractMap.SimpleEntry<Long, Integer>(maxEpoch, maxNum);
    }

    public static Map<Integer, TopicQueueMappingOne> buildMappingItems(List<TopicQueueMappingDetail> mappingDetailList, boolean replace, boolean checkConsistence) {
        Collections.sort(mappingDetailList, new Comparator<TopicQueueMappingDetail>() {
            @Override
            public int compare(TopicQueueMappingDetail o1, TopicQueueMappingDetail o2) {
                return (int)(o2.getEpoch() - o1.getEpoch());
            }
        });

        int maxNum = 0;
        Map<Integer, TopicQueueMappingOne> globalIdMap = new HashMap<Integer, TopicQueueMappingOne>();
        for (TopicQueueMappingDetail mappingDetail : mappingDetailList) {
            if (mappingDetail.totalQueues > maxNum) {
                maxNum = mappingDetail.totalQueues;
            }
            for (Map.Entry<Integer, ImmutableList<LogicQueueMappingItem>>  entry : mappingDetail.getHostedQueues().entrySet()) {
                Integer globalid = entry.getKey();
                String leaderBrokerName  = getLeaderBroker(entry.getValue());
                if (!leaderBrokerName.equals(mappingDetail.getBname())) {
                    //not the leader
                    continue;
                }
                if (globalIdMap.containsKey(globalid)) {
                    if (!replace) {
                        throw new RuntimeException(String.format("The queue id is duplicated in broker %s %s", leaderBrokerName, mappingDetail.getBname()));
                    }
                } else {
                    globalIdMap.put(globalid, new TopicQueueMappingOne(mappingDetail.topic, mappingDetail.bname, globalid, entry.getValue()));
                }
            }
        }
        if (checkConsistence) {
            if (maxNum != globalIdMap.size()) {
                throw new RuntimeException(String.format("The total queue number in config dose not match the real hosted queues %d != %d", maxNum, globalIdMap.size()));
            }
            for (int i = 0; i < maxNum; i++) {
                if (!globalIdMap.containsKey(i)) {
                    throw new RuntimeException(String.format("The queue number %s is not in globalIdMap", i));
                }
            }
        }
        return globalIdMap;
    }

    public static String getLeaderBroker(ImmutableList<LogicQueueMappingItem> items) {
        return getLeaderItem(items).getBname();
    }
    public static LogicQueueMappingItem getLeaderItem(ImmutableList<LogicQueueMappingItem> items) {
        assert items.size() > 0;
        return items.get(items.size() - 1);
    }

    public static String writeToTemp(TopicRemappingDetailWrapper wrapper, String suffix) {
        String topic = wrapper.getTopic();
        String data = wrapper.toJson();
        String fileName = System.getProperty("java.io.tmpdir") + File.separator + topic + "-" + wrapper.getEpoch() + "-" + suffix;
        try {
            MixAll.string2File(data, fileName);
            return fileName;
        } catch (Exception e) {
            throw new RuntimeException("write file failed " + fileName,e);
        }
    }



}
