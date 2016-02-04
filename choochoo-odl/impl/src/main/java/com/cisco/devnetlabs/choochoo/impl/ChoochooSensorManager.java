/*
 * Copyright (c) 2015 Cisco Systems, and others and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package com.cisco.devnetlabs.choochoo.impl;

import org.json.JSONObject;
import org.opendaylight.iotdm.onem2m.client.*;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.Onem2mService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The ChoochooSensor class manages the handling of the sensor messages received via MQTT messages.  There are 12
 * sensors around the track which is acbout 76 feet long.  There a 4 arduino's each with 3 sensors on them.  This code
 * will listen as a train nears the sensor. When this happens, we react by updating the oneM2M data store with the
 * curent sesnor id.  Then if the sensor [1...12] is even, we will blow the train's horn.  And if it odd, we will
 * turn on the light.  When the train goes away from teh sensor, we will stop the horn or light.  This quite simple
 * but it illustrates adding data to an IOT datastore in oneM2M format, as well as reacting to IOT stimulus'.  IN a
 * real train network, any number of actions can take place.  They can detect the speed of the train and initiate
 * actions if the train is going too fast, etc.
 */
public class ChoochooSensorManager {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ChoochooSensorManager.class);
    private Onem2mService onem2mService;
    private ChoochooTrainManager choochooTrainManager = null;
    private Integer saveBlockId = 0;
    private Integer savePosId = 0;

    private static final String CHOOCHOO_ROOT = "choochoo";
    private static final String CHOOCHOO_SENSOR = "sensor";

    public ChoochooSensorManager(Onem2mService onem2mService, ChoochooTrainManager choochooTrainManager) {

        this.onem2mService = onem2mService;
        this.choochooTrainManager = choochooTrainManager;
        LOG.info("Created ChoochooSensorManager");
    }

    public void initializeDataStore() {
        initializeOnem2mSensorTree();
    }

    public void cleanupDataStore() {
    }

    private boolean initializeOnem2mSensorTree() {

        // see if the resource tree exists, if not provision it.
        if (!getCse()) {
            if (!provisionCse()) {
                return false;
            }

            if (!createContainer(CHOOCHOO_ROOT, CHOOCHOO_SENSOR, 1) ) {
                return false;
            }
        }

        return true;
    }

    /**
     * This code is called from the mqtt plugin.  It has a JSON represntation of some sensor parameters.  Don't worry
     * too much what the parameters are.  We know that the blockId, and pos can tell us which sensor in the range of
     * 1...12.  We will read the current sensor so we know if we transitioned from the train approaching a sensor, or
     * going out of range of the sensor.
     * @param topic
     * @param sensorId
     */
    void processSensor(String topic, Integer sensorId) {


        String target = "/" + CHOOCHOO_ROOT + "/" + CHOOCHOO_SENSOR;

        // update latest value of the sendorId in the onem2m db
        if (!createContentInstance(target, sensorId.toString())) {
            LOG.info("processSensor: error adding {}:{}", target, sensorId.toString());
        } else {
            if (sensorId % 2 == 1) {
                choochooTrainManager.setHorn(false);
                choochooTrainManager.setLight(true);
                LOG.info("processSensor: sensorId: {}, turn on lights, turn off horn", sensorId);
            } else if (sensorId % 2 == 0) {
                choochooTrainManager.setHorn(true);
                choochooTrainManager.setLight(false);
                LOG.info("processSensor: sensorId: {}, turn on horn, turn off lights", sensorId);
            }
        }
    }

    private boolean getCse() {

        Onem2mRequestPrimitiveClient req = new Onem2mCSERequestBuilder().setOperationRetrieve().setTo(CHOOCHOO_ROOT).build();
        Onem2mResponsePrimitiveClient res = req.send(onem2mService);
        if (!res.responseOk()) {
            LOG.info(res.getContent());
            return false;
        }
        Onem2mCSEResponse cseResponse = new Onem2mCSEResponse(res.getContent());
        if (!cseResponse.responseOk()) {
            LOG.info(res.getError());
            return false;
        }
        String resourceId = cseResponse.getResourceId();
        if (resourceId == null) {
            LOG.info("getCse: Create cannot parse resourceId for CSE get");
            return false;
        }

        return true;
    }

    private boolean provisionCse() {

        Onem2mCSERequestBuilder b;

        b = new Onem2mCSERequestBuilder();
        b.setTo(CHOOCHOO_ROOT);     // M
        b.setCseId(CHOOCHOO_ROOT);  // M
        b.setCseType("IN_CSE");// O
        b.setName(CHOOCHOO_ROOT);   // M
        b.setOperationCreate();// M
        Onem2mRequestPrimitiveClient req = b.build();
        Onem2mResponsePrimitiveClient res = req.send(onem2mService);
        if (!res.responseOk()) {
            LOG.error(res.getError());
            return false;
        }
        Onem2mCSEResponse cseResponse = new Onem2mCSEResponse(res.getContent());
        if (!cseResponse.responseOk()) {
            LOG.error(res.getError());
            return false;
        }
        String resourceId = cseResponse.getResourceId();
        if (resourceId == null) {
            LOG.error("provisionCse: Create cannot parse resourceId for CSE provision");
            return false;
        }

        return true;
    }

    private boolean getContainer(String parent, String name) {

        Onem2mContainerRequestBuilder b;

        b = new Onem2mContainerRequestBuilder();
        b.setTo(parent + "/" + name);
        b.setOperationRetrieve();
        Onem2mRequestPrimitiveClient req = b.build();
        Onem2mResponsePrimitiveClient res = req.send(onem2mService);
        if (!res.responseOk()) {
            //LOG.error(res.getError());
            return false;
        }
        Onem2mContainerResponse ctrResponse = new Onem2mContainerResponse(res.getContent());
        if (!ctrResponse.responseOk()) {
            LOG.error("Container get request: {}", ctrResponse.getError());
            return false;
        }

        String resourceId = ctrResponse.getResourceId();
        if (resourceId == null) {
            LOG.error("get cannot parse resourceId for Container create");
            return false;
        }

        LOG.info("getContainer {}/{}: Curr/Max Nr Instances: {}/{}, curr/Max ByteSize: {}/{}",
                parent, name,
                ctrResponse.getCurrNrInstances(),
                ctrResponse.getMaxNrInstances(),
                ctrResponse.getCurrByteSize(),
                ctrResponse.getMaxByteSize());

        return true;
    }

    private boolean createContainer(String parent, String name, int maxNumInstances) {

        Onem2mContainerRequestBuilder b;

        b = new Onem2mContainerRequestBuilder();
        b.setTo(parent);
        b.setOperationCreate();
        if (maxNumInstances != -1) b.setMaxNrInstances(maxNumInstances);
        b.setName(name);
        Onem2mRequestPrimitiveClient req = b.build();
        Onem2mResponsePrimitiveClient res = req.send(onem2mService);
        if (!res.responseOk()) {
            LOG.error(res.getError());
            return false;
        }
        Onem2mContainerResponse ctrResponse = new Onem2mContainerResponse(res.getContent());
        if (!ctrResponse.responseOk()) {
            LOG.error("Container create request: {}", ctrResponse.getError());
            return false;
        }

        String resourceId = ctrResponse.getResourceId();
        if (resourceId == null) {
            LOG.error("Create cannot parse resourceId for Container create");
            return false;
        }

        LOG.info("createContainer {}/{}: Curr/Max Nr Instances: {}/{}, curr/Max ByteSize: {}/{}",
                parent, name,
                ctrResponse.getCurrNrInstances(),
                ctrResponse.getMaxNrInstances(),
                ctrResponse.getCurrByteSize(),
                ctrResponse.getMaxByteSize());

        return true;
    }


    private boolean createContentInstance(String parent, String content) {

        Onem2mContentInstanceRequestBuilder b;

        b = new Onem2mContentInstanceRequestBuilder();
        b.setTo(parent);
        b.setOperationCreate();
        b.setContent(content);
        Onem2mRequestPrimitiveClient req = b.build();
        Onem2mResponsePrimitiveClient res = req.send(onem2mService);
        if (!res.responseOk()) {
            LOG.error(res.getError());
            return false;
        }
        Onem2mContentInstanceResponse ciResponse = new Onem2mContentInstanceResponse(res.getContent());
        if (!ciResponse.responseOk()) {
            LOG.error("Container create request: {}", ciResponse.getError());
            return false;
        }

        String resourceId = ciResponse.getResourceId();
        if (resourceId == null) {
            LOG.error("Create cannot parse resourceId for ContentInstance create");
            return false;
        }

        LOG.info("createContentInstance: Curr ContentSize: {}\n", ciResponse.getContentSize());

        return true;
    }

    private String getContentInstanceLatest(String target) {

        Onem2mContentInstanceRequestBuilder b;

        b = new Onem2mContentInstanceRequestBuilder();
        b.setTo(target + "/latest");
        b.setOperationRetrieve();
        Onem2mRequestPrimitiveClient req = b.build();
        Onem2mResponsePrimitiveClient res = req.send(onem2mService);
        if (!res.responseOk()) {
            //LOG.error(res.getError());
            return null;
        }
        Onem2mContentInstanceResponse ciResponse = new Onem2mContentInstanceResponse(res.getContent());
        if (!ciResponse.responseOk()) {
            LOG.error("Container create request: {}", ciResponse.getError());
            return null;
        }

        return ciResponse.getCIContent();
    }
}
