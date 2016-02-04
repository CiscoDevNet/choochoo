/*
 * Copyright (c) 2015 Cisco Systems, and others and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package com.cisco.devnetlabs.choochoo.impl;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;

import com.google.common.util.concurrent.Monitor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;

import netscape.javascript.JSObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.opendaylight.controller.md.sal.binding.api.*;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker.ProviderContext;
import org.opendaylight.controller.sal.binding.api.BindingAwareProvider;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ControlTrainInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ControlTrainInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ControlTrainOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ControlTrainOutputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.TrainTopology;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.TrainTopologyBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.control.parms.list.ControlParm;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.train.topology.Train;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.train.topology.TrainBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.train.topology.TrainKey;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChoochooTrainManager implements DataTreeChangeListener<TrainTopology> {

    private static final Logger LOG = LoggerFactory.getLogger(ChoochooTrainManager.class);
    private ChooChooHttpClient client;
    private DataBroker dataBroker;
    private Monitor crudMonitor;
    private String trainControllerIpaddress = null;
    private String trainDefaultLocoId = null;
    private static final InstanceIdentifier<TrainTopology> TRAIN_TOPOLOGY_IID =
            InstanceIdentifier.builder(TrainTopology.class).build();
    private ListenerRegistration<ChoochooTrainManager> dcReg;

    public ChoochooTrainManager(DataBroker dataBroker) {

        this.dataBroker = dataBroker;
        this.crudMonitor = new Monitor();
        client = new ChooChooHttpClient();
        dcReg = dataBroker.registerDataTreeChangeListener(new DataTreeIdentifier<>(LogicalDatastoreType.CONFIGURATION,
                TRAIN_TOPOLOGY_IID), this);
        LOG.info("Created ChoochooTrainManager");
    }
    
    public void close() throws Exception {
        client.stop();
        LOG.info("ChoochooTrainManager Closed");
    }
    
    public void cleanupDataStore() {
        cleanupTopology();
    }

    /**
     * At some point, must figure out if the data store already has info in the data store, and NOT overwrite it
     */
    public void initializeDatastore() {
        InstanceIdentifier<TrainTopology> iid = InstanceIdentifier.builder(TrainTopology.class).build();
        TrainTopology dt = new TrainTopologyBuilder().setTrain(Collections.<Train>emptyList()).build();

        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        
        // if the db already exists then this merge should be harmless and ensures we do not overwrite the data store
        // otherwise, if the data store is empty, it gets initialized with empty
        tx.merge(LogicalDatastoreType.OPERATIONAL, iid, dt);
        tx.merge(LogicalDatastoreType.CONFIGURATION, iid, dt);
        tx.submit();

        initializeTrainParms();
    }

    public void initializeTrainParms() {

//        WriteTransaction tx = dataBroker.newWriteOnlyTransaction();
//
//        TrainTopology tt = new TrainTopologyBuilder()
//                .setTrainController("171.68.22.52:5000")
//                .setDefaultLocoId("5")
//                .build();
//
//        tx.put(LogicalDatastoreType.CONFIGURATION, TRAIN_TOPOLOGY_IID, tt);
//        tx.submit();
    }

    private void cleanupTopology() {
        InstanceIdentifier<TrainTopology> iid = InstanceIdentifier.builder(TrainTopology.class).build();
        TrainTopology dt = new TrainTopologyBuilder().setTrain(Collections.<Train>emptyList()).build();

        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();

        tx.put(LogicalDatastoreType.OPERATIONAL, iid, dt);
        tx.put(LogicalDatastoreType.CONFIGURATION, iid, dt);
        tx.submit();
    }

    @Override
    public void onDataTreeChanged(Collection<DataTreeModification<TrainTopology>> changes) {
        LOG.info("ChoochooProvider: OnDataTreeChanged(TrainTopology) called");
        for (DataTreeModification<TrainTopology> change : changes) {
            switch (change.getRootNode().getModificationType()) {
                case WRITE:
                case SUBTREE_MODIFIED:
                    TrainTopology tt = change.getRootNode().getDataAfter();
                    trainControllerChanged(tt);
                    break;
                case DELETE:
                    trainControllerDeleted();
                    break;
                default:
                    LOG.error("ChoochooProvider: OnDataTreeChanged(TrainTopology) non handled modification {}",
                            change.getRootNode().getModificationType());
                    break;
            }
        }
    }

    public void trainControllerChanged(TrainTopology tt) {

        LOG.info("trainControllerChanged: {}", tt.getTrainController());
        trainControllerIpaddress = tt.getTrainController();
        trainDefaultLocoId = tt.getDefaultLocoId();
        getTrainsFromServer();

    }

    public void trainControllerDeleted() {

        LOG.info("trainControllerDeleted:");
        trainControllerIpaddress = null;
        trainDefaultLocoId = null;
    }

    private TrainTopology readTrainTopology(LogicalDatastoreType ldsType) {

        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();

        InstanceIdentifier<TrainTopology> iid = InstanceIdentifier.create(TrainTopology.class);

        TrainTopology tt = null;

        Optional<TrainTopology> optionalDataObject;
        CheckedFuture<Optional<TrainTopology>, ReadFailedException> submitFuture = tx.read(ldsType, iid);
        try {
            optionalDataObject = submitFuture.checkedGet();
            if (optionalDataObject != null && optionalDataObject.isPresent()) {
                tt = optionalDataObject.get();
            }
        } catch (ReadFailedException e) {
            LOG.error("failed to ....", e);
        }

        LOG.info("read: traintopology");

        return tt;
    }

    private Train readTrain(String locoId, LogicalDatastoreType ldsType) {

        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();

        InstanceIdentifier<Train> iid = InstanceIdentifier.create(TrainTopology.class)
                .child(Train.class, new TrainKey(locoId));

        Train train = null;

        Optional<Train> optionalDataObject;
        CheckedFuture<Optional<Train>, ReadFailedException> submitFuture = tx.read(ldsType, iid);
        try {
            optionalDataObject = submitFuture.checkedGet();
            if (optionalDataObject != null && optionalDataObject.isPresent()) {
                train = optionalDataObject.get();
            }
        } catch (ReadFailedException e) {
            LOG.error("failed to ....", e);
        }

        LOG.info("read: train: {}", locoId);

        return train;
    }

    private void createUpdateTrain(String locoId, JSONObject jTrainParms) {

        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();

        InstanceIdentifier<Train> iid;
        
        iid = InstanceIdentifier.create(TrainTopology.class).child(Train.class, new TrainKey(locoId));
        
        Train train = new TrainBuilder()
                        .setLocoId(locoId)
                        .setKey(new TrainKey(locoId))
                       // .setParms(jTrainParms.toString())
                        //.setObdPids(input.getObdPids())
                        .build();

        tx.merge(LogicalDatastoreType.OPERATIONAL, iid, train);
        tx.submit();
        LOG.info("write: OPERATIONAL train: {}", train);

    }

    private void deleteTrain(String locoId) {

        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();

        InstanceIdentifier<Train> iid;

        iid = InstanceIdentifier.create(TrainTopology.class).child(Train.class, new TrainKey(locoId));

        tx.delete(LogicalDatastoreType.OPERATIONAL, iid);
        tx.submit();
    }

    public void setSpeed(Integer speed) {
        JSONObject jTrain = new JSONObject();
        jTrain.put("speed", speed);
        sendControlCommandToServer(trainDefaultLocoId, jTrain.toString());
    }
    public void setLight(boolean turnOn) {
        JSONObject jTrain = new JSONObject();
        jTrain.put("headlight", turnOn ? "on" : "off");
        sendControlCommandToServer(trainDefaultLocoId, jTrain.toString());
    }
    public void setHorn(boolean turnOn) {
        JSONObject jTrain = new JSONObject();
        jTrain.put("bell", turnOn ? "on" : "off");
        sendControlCommandToServer(trainDefaultLocoId, jTrain.toString());
    }
    
    public void sendControlCommandToServer(String locoId, String content) {

        LOG.info("sendControlCommandToServer: sending {} to train controller: {}", content, trainControllerIpaddress);
        if (trainControllerIpaddress == null) {
            return;
        }

        ContentExchange httpRequest = new ContentExchange(true);
        String url = "http://" + trainControllerIpaddress + "/loco/" + locoId;
        httpRequest.setMethod("POST");
        Integer cl = content != null ?  content.length() : 0;
        httpRequest.setRequestHeader("Content-Length", cl.toString());
        httpRequest.setRequestContentSource(new ByteArrayInputStream(content.getBytes()));
        httpRequest.setRequestContentType("application/json");
        LOG.info("sendControlCommandToServer: sending http request: {}", httpRequest.toString());

        ContentExchange httpResponse = client.sendRequest(url, httpRequest);
        try {
            String responseContent = httpResponse.getResponseContent();
            int rsc = httpResponse.getResponseStatus();
            LOG.info("sendControlCommandToServer: responseStatus: {}, responseContent: {}",
                    rsc, responseContent);
            if (rsc < 200 || rsc >= 300) {
                LOG.error("Cannot sendControlCommandToServer ...");
                return;
            }
        } catch (UnsupportedEncodingException e) {
            LOG.error("get http content exception: {}", e.toString());
        }
    }

    public void getTrainsFromServer() {

        if (trainControllerIpaddress == null) {
            return;
        }
        ContentExchange httpRequest = new ContentExchange(true);
        String url = "http://" + trainControllerIpaddress + "/locos";
        httpRequest.setMethod("GET");
        LOG.info("getTrainsFromServer: sending http request: {}", httpRequest.toString());

        String jsonContent = client.handleRequest(url, httpRequest);
        if (jsonContent == null) {
            LOG.error("getTrainsFromServer: error retrieving trains from controller: {}", trainControllerIpaddress);
        } else {
            handleTrainInventory(jsonContent);
        }
    }

    private String getLocoIdFromJsonObject(JSONObject jTrain) {
        Iterator<?> keys = jTrain.keys();
        while( keys.hasNext() ) {
            String key = (String)keys.next();
            return key;
        }
        return null;
    }

    private void handleTrainInventory(String trainJsonString) {

        /**
         * Put all datastore entries into HashSet then as each train is discovered from the network, take it
         * out of the set.  Finally, any trains left in the set should be deleted as they are no longer
         * discoverable.
         */

        /**
         * Create the hashset, read trains from the datastore and add to set
         */
        HashSet<String> trainSet = new HashSet<String>();
        TrainTopology trainTopology = readTrainTopology(LogicalDatastoreType.OPERATIONAL);
        List<Train> trainList = trainTopology.getTrain();
        for (Train train : trainList) {
            trainSet.add(train.getLocoId());
        }
        LOG.info("handleTrainInventory: added {} trains to the hashSet", trainSet.size());

        /**
         * Now handle the jsonString which was read in from the the train controller.  For each train in the json
         * array, pull out the locoId, and trainParms and add to the data store, and remove the locoId from the
         * hashSet.
         */
        JSONArray jTrainArray = null;

        try {
            jTrainArray = new JSONArray(trainJsonString);
        } catch (JSONException e) {
            LOG.error("handleTrainInventory: issues parsing {}", e.toString());
            return;
        }
        for (int i = 0; i < jTrainArray.length(); i++) {
            if (!(jTrainArray.get(i) instanceof JSONObject)) {
                LOG.error("JSON object expected for json array instance i={}: " + i);
                return;
            }
            JSONObject jTrain = (JSONObject) jTrainArray.get(i);
            String locoId = getLocoIdFromJsonObject(jTrain);
            if (locoId != null) {
                LOG.info("handleTrainInventory: add/update train {}", locoId);
                JSONObject jTrainParms = jTrain.optJSONObject("locoId");
                createUpdateTrain(locoId, jTrainParms);
                trainSet.remove(locoId);
            }
        }

        /**
         * Now for each entry in the remaining in the hash set ... delete
         */
        for (String locoId : trainSet) {
            deleteTrain(locoId);
            LOG.info("handleTrainInventory: removing train {} as train controller does not have it anymore", locoId);
        }
    }

    public ControlTrainOutput controlTrain(ControlTrainInput input) {

        ControlTrainOutput output = null;

        String locoId = input.getLocoId();
        if (locoId == null) {
            LOG.error("controlTrain: invalid (null) locoId");
            output = new ControlTrainOutputBuilder()
                    .setStatus(ControlTrainOutput.Status.FAILED)
                    .build();
            return output;
        }

        List<ControlParm> cpList = input.getControlParm();
        for (ControlParm cp : cpList) {
            JSONObject jChooChoo = null;
            try {
                jChooChoo = new JSONObject(cp.getContentJsonString());
            } catch (JSONException e) {
                LOG.error("{}", e.toString());
            }
            sendControlCommandToServer(locoId, cp.getContentJsonString());
        }

        output = new ControlTrainOutputBuilder()
                .setStatus(ControlTrainOutput.Status.OK)
                .build();
        return output;
    }

    public class ChooChooHttpClient {

        private final Logger LOG = LoggerFactory.getLogger(ChooChooHttpClient.class);

        private HttpClient httpClient;

        public ChooChooHttpClient() {
            httpClient = new HttpClient();
            try {
                httpClient.start();
            } catch (Exception e) {
                LOG.error("Issue starting httpClient: {}", e.toString());
            }
        }

        public ContentExchange sendRequest(String url, ContentExchange httpRequest) {

            httpRequest.setURL(url);
            try {
                httpClient.send(httpRequest);
            } catch (IOException e) {
                LOG.error("Issues with httpClient.send: {}", e.toString());
            }
            int ex = HttpExchange.STATUS_EXCEPTED;
            // Waits until the exchange is terminated
            try {
                ex = httpRequest.waitForDone();
            } catch (InterruptedException e) {
                LOG.error("Issues with waitForDone: {}", e.toString());
            }
            return httpRequest;
        }

        public String handleRequest(String url, ContentExchange httpRequest) {

            ContentExchange httpResponse = sendRequest(url, httpRequest);

            try {
                String responseContent = httpResponse.getResponseContent();
                int rsc = httpResponse.getResponseStatus();
                LOG.info("handleRequest: responseStatus: {}, responseContent: {}", rsc, responseContent);
                if (rsc < 200 || rsc >= 300) {
                    LOG.error("handleRequest: httpStatusCode: {} ...", rsc);
                    return null;
                }
                LOG.info("handleRequest:content: {}", responseContent);
                return responseContent;
            } catch (UnsupportedEncodingException e) {
                LOG.error("get http content exception: {}", e.toString());
            }

            return null;
        }

        public void stop() throws Exception {
            httpClient.stop();
        }
    }
}