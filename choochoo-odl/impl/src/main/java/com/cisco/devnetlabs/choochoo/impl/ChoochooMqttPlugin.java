/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package com.cisco.devnetlabs.choochoo.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.paho.client.mqttv3.*;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Seconds;
import org.json.JSONException;
import org.json.JSONObject;
import org.opendaylight.controller.md.sal.binding.api.*;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.MqttParms;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.MqttParmsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.mqtt.parms.SubscriberTopics;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.mqtt.parms.SubscriberTopicsBuilder;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChoochooMqttPlugin implements MqttCallback, DataTreeChangeListener<MqttParms> {

    private static final Logger LOG = LoggerFactory.getLogger(ChoochooMqttPlugin.class);
    private final ExecutorService executor;
    protected ChoochooSensorManager onem2mManager;
    protected static HashSet<String> subscriberTopicList = null;
    private String mqttBroker;
    protected static Boolean connectedToBroker = false;
    private MqttClient mqttClient;
    private static final InstanceIdentifier<MqttParms> MQTTPARMS_IID =
            InstanceIdentifier.builder(MqttParms.class).build();
    private ListenerRegistration<ChoochooMqttPlugin> dcReg;
    private DataBroker dataBroker;
    private DateTime saveTime = new DateTime(DateTimeZone.UTC);;
    private Integer saveBlockId = 0;
    private Integer savePosId = 0;

    public ChoochooMqttPlugin(DataBroker dataBroker, ChoochooSensorManager onem2mManager) {
        executor = Executors.newFixedThreadPool(1);
        mqttBroker = null;
        this.dataBroker = dataBroker;
        subscriberTopicList = new HashSet();
        this.onem2mManager = onem2mManager;
        mqttClient = null;
        dcReg = dataBroker.registerDataTreeChangeListener(new DataTreeIdentifier<>(LogicalDatastoreType.CONFIGURATION,
                MQTTPARMS_IID), this);
    }

    public void close() {
        dcReg.close();
    }

    public void initializeMqttParms() {

        WriteTransaction tx = dataBroker.newWriteOnlyTransaction();
        List<SubscriberTopics> subList = new ArrayList<SubscriberTopics>();
        SubscriberTopics subt = new SubscriberTopicsBuilder()
                .setTopic("devnet/#")
                .build();
        subList.add(subt);
        MqttParms sensorParms = new MqttParmsBuilder()
                //.setMqttBroker("tcp://localhost:7777")
                .setMqttBroker("tcp://mqtt.cisco.com:7777")
                .setSubscriberTopics(subList)
                        //.setSubscriberTopics(Collections.<SubscriberTopics>emptyList())
                .build();
        tx.put(LogicalDatastoreType.CONFIGURATION, MQTTPARMS_IID, sensorParms);
        tx.submit();
    }

    private void disconnectFromMqttServer() {
        try {
            if (mqttBroker != null && mqttClient != null) {
                mqttClient.disconnect();
                mqttClient = null;
                subscriberTopicList.clear();
                LOG.info("disconnectFromMqttServer: disconnecting from {}", mqttBroker);
            }
        } catch (MqttException e) {
            dumpExceptionToLog("disconnectFromMqttServer: trouble disconnecing", e);

        } finally {
            connectedToBroker = false;
        }
    }

    private void subscribeToTopic(String topic) {
        if (connectedToBroker) {
            try {
                mqttClient.subscribe(topic, 1);
                LOG.info("subscribe: topic: {}", topic);
            } catch (MqttException e) {
                dumpExceptionToLog("subscribeToTopic: trouble subscribing: " + topic, e);
            }
        }
    }

    private void unsubscribeToTopic(String topic) {
        if (connectedToBroker) {
            try {
                mqttClient.unsubscribe(topic);
                LOG.info("unsubscribe: topic: {}", topic);
            } catch (MqttException e) {
                dumpExceptionToLog("unsubscribeToTopic: trouble unsubscribing: " + topic, e);
            }
        }
    }

    @Override
    public void onDataTreeChanged(Collection<DataTreeModification<MqttParms>> changes) {
        LOG.info("ChoochooProvider: OnDataTreeChanged(MqttParms) called");
        for (DataTreeModification<MqttParms> change : changes) {
            switch (change.getRootNode().getModificationType()) {
                case WRITE:
                case SUBTREE_MODIFIED:
                    MqttParms mqttParms = change.getRootNode().getDataAfter();
                    mqttParmsChanged(mqttParms);
                    break;
                case DELETE:
                    mqttParmsDeleted();
                    break;
                default:
                    LOG.error("ChoochooProvider: OnDataTreeChanged(MqttParms) non handled modification {}",
                            change.getRootNode().getModificationType());
                    break;
            }
        }
    }

    public void mqttParmsChanged(MqttParms mqttParms) {

        LOG.info("MqttParmsChanged: {}", mqttParms.getMqttBroker());
        if (mqttParms.getMqttBroker() != null && (mqttBroker == null ||
                !mqttParms.getMqttBroker().contentEquals(mqttBroker))) {
            disconnectFromMqttServer();
            this.mqttBroker = mqttParms.getMqttBroker();
            try {
                connectToMqttServer();
            } catch (MqttException e) {
                dumpExceptionToLog("MqttParmsChanged: trouble connecting: " + mqttBroker, e);
            }
        }
        if (connectedToBroker) {
            HashSet<String> tempSet = new HashSet();
            List<SubscriberTopics> topics = mqttParms.getSubscriberTopics();
            for (SubscriberTopics mqttTopic : topics) {
                String topic = mqttTopic.getTopic();
                LOG.info("MqttParmsChanged: {}", mqttTopic.getTopic());
                if (!subscriberTopicList.contains(topic)) {
                    subscribeToTopic(mqttTopic.getTopic());
                } else {
                    subscriberTopicList.remove(topic);
                }
                tempSet.add(topic);
            }
            for (String topic : subscriberTopicList) {
                unsubscribeToTopic(topic);
            }
            subscriberTopicList = tempSet;
        }
    }

    public void mqttParmsDeleted() {

        LOG.info("mqttParmsDeleted:");
        disconnectFromMqttServer();
        mqttBroker = null;
        subscriberTopicList.clear();
    }


    private void connectToMqttServer() throws MqttException {

        connectedToBroker = false;
        while (!connectedToBroker) {
            try {
                mqttClient = new MqttClient(mqttBroker, MqttClient.generateClientId(), null);
                mqttClient.setCallback(this);
                mqttClient.connect();

                //connecting client to server
                if (!mqttClient.isConnected()) {
                    LOG.error("connectToMqttServer: trouble connecting to server");
                } else {
                    connectedToBroker = true;
                    LOG.info("connectToMqttServer: connected to broker: {}", mqttBroker);
                    return;
                }

            } catch (MqttException e) {
                dumpExceptionToLog("connectToMqttServer: trouble connecting to server, will retry ...", e);
                mqttClient = null;
            }
            pause();
        }
    }

    private void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Error handling goes here...
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        LOG.error("connectToMqttServer: lost connection to server");
        try {
            connectToMqttServer();
            for (String topic : subscriberTopicList) {
                subscribeToTopic(topic);
            }
        } catch (MqttException e) {
            dumpExceptionToLog("connectToMqttServer", e);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        handleMqttMessage(topic, message.toString());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {//Called when a outgoing publish is complete.
    }

    private void dumpExceptionToLog(String log, MqttException e) {
        LOG.error("{}: {}", log, e.toString());
    }

    private void handleMqttMessage(String topic, String message) {

        //LOG.info("handleMqttMessage: topic: {}, message: {}", topic, message);
        JSONObject jSensor = null;
        try {
            jSensor = new JSONObject(message);
        } catch (JSONException e) {
            LOG.error("{}", e.toString());
            return;
        }

        String sensorBlockId = jSensor.optString("block", null);
        if (sensorBlockId == null) {
            LOG.error("processSensor: missing block in Json String: {}", jSensor.toString());
            return;
        }
        String blockPosition = jSensor.optString("pos", null);
        if (blockPosition == null) {
            LOG.error("processSensor: missing pos in Json String: {}", jSensor.toString());
            return;
        } else if (blockPosition.contentEquals("0")) {
            savePosId = 0;
            return;
        }

        DateTime currTime = new DateTime(DateTimeZone.UTC);
        long diff = Seconds.secondsBetween(saveTime, currTime).getSeconds()%60;
        if (diff < 2) return; // only sample sensor values at most every 2 seconds
        saveTime = currTime;

        // if nothing has changed, return as we have alredy handled entering this state
        if (saveBlockId == Integer.valueOf(sensorBlockId) && savePosId == Integer.valueOf(blockPosition)) {
            return;
        }

        saveBlockId = Integer.valueOf(sensorBlockId);
        savePosId = Integer.valueOf(blockPosition);

        Integer sensorId = (Integer.valueOf(sensorBlockId)-1)*3 + Integer.valueOf(blockPosition);

        onem2mManager.processSensor(topic, sensorId);
    }

}