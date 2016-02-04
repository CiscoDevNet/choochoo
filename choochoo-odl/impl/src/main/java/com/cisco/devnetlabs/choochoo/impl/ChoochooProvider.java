/*
 * Copyright © 2015 Cisco Systems, Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package com.cisco.devnetlabs.choochoo.impl;

import java.util.concurrent.Future;
import org.opendaylight.controller.md.sal.binding.api.*;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker.ProviderContext;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker.RpcRegistration;
import org.opendaylight.controller.sal.binding.api.BindingAwareProvider;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ChoochooService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ControlTrainInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ControlTrainOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.Onem2mService;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChoochooProvider implements ChoochooService, BindingAwareProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChoochooProvider.class);
    private ChoochooTrainManager choochooTrainManager = null;
    private ChoochooSensorManager choochooSensorManager = null;
    private ChoochooMqttPlugin mqttPlugin = null;
    private DataBroker dataBroker;
    private RpcRegistration<ChoochooService> rpcReg;

    /*
        This class is automatically called when the system comes up.  It is analogous to you main entry point.
        The dataBroker is accessed and passed to the ChoochooTrainManager.  It is used to write train data to the
        datastore.  The choochoo.yang file describes the data model.  The rpcReg is a call to register this class
        to be called to implement the RPCs defined in the choochoo.yang file.  The sensor data is mananged by the
        ChoochooSensorManager.  It receives sensor data from the network, stores in in an IOT database and implements
        some control logic to change the state of the train on the track.
     */
    @Override
    public void onSessionInitiated(ProviderContext session) {

        dataBroker = session.getSALService(DataBroker.class);
        rpcReg = session.addRpcImplementation(ChoochooService.class, this);
        choochooTrainManager = new ChoochooTrainManager(dataBroker);
        Onem2mService onem2mService = session.getRpcService(Onem2mService.class);
        choochooSensorManager = new ChoochooSensorManager(onem2mService, choochooTrainManager);
        mqttPlugin = new ChoochooMqttPlugin(dataBroker, choochooSensorManager);

        initializeDb();

        LOG.info("ChoochooProvider Session Initiated");
    }

    /*
        Called when the system is shutting down.
     */
    @Override
    public void close() throws Exception {
        rpcReg.close();
        mqttPlugin.close();

        LOG.info("ChoochooProvider Closed");
    }

    /**
     * The database has a few moving parts.  First, the train topology.  It stores the list of trains discovered
     * from the train server.  See choochoo.yang.  In order to discover the trains, the train-controller has to be
     * configured.  Another data store that we keep is a basic sensor database.  Given that it is IOT sensor data, I
     * thought I would maintain sensor data in the oneM2M datasore - see ChoochooSensorManager.  Also, in order to
     * receive sensor data, the address of the mqtt broker must be configured.
     */
    private void initializeDb() {
        choochooSensorManager.initializeDataStore();
        choochooTrainManager.initializeDatastore();
        mqttPlugin.initializeMqttParms();
    }

//    /**
//     * Clean data store ... commenting this out for now as somebody might do this to the live train.
//     *
//     */
//    @Override
//    public Future<RpcResult<Void>> cleanupStore() {
//        choochooTrainManager.cleanupDataStore();
//        choochooTrainManager.initializeDatastore();
//        choochooSensorManager.cleanupDataStore();
//        choochooSensorManager.initializeDataStore();
//
//        return Futures.immediateFuture(RpcResultBuilder.<Void>success().build());
//    }

    /**
     * Control a train in the topology
     */
    @Override
    public Future<RpcResult<ControlTrainOutput>> controlTrain(ControlTrainInput input) {
        ControlTrainOutput output =  choochooTrainManager.controlTrain(input);
        return RpcResultBuilder.success(output).buildFuture();
    }
}
