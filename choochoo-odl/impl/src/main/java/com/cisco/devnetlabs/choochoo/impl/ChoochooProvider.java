/*
 * Copyright © 2015 Copyright (c) 2015 Cisco Systems, Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package com.cisco.devnetlabs.choochoo.impl;

import com.google.common.util.concurrent.Futures;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import org.opendaylight.controller.md.sal.binding.api.*;
import org.opendaylight.controller.md.sal.binding.api.DataObjectModification.ModificationType;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker.ProviderContext;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker.RpcRegistration;
import org.opendaylight.controller.sal.binding.api.BindingAwareProvider;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ChoochooService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ControlTrainInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.ControlTrainOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.MqttParms;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.MqttParmsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.TrainTopology;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.mqtt.parms.SubscriberTopics;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.choochoo.rev150105.mqtt.parms.SubscriberTopicsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.Onem2mService;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
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

    /**
     * clean data store
     *
     */
    @Override
    public Future<RpcResult<Void>> cleanupStore() {
        choochooTrainManager.cleanupDataStore();
        choochooTrainManager.initializeDatastore();
        choochooSensorManager.cleanupDataStore();
        choochooSensorManager.initializeDataStore();

        return Futures.immediateFuture(RpcResultBuilder.<Void>success().build());
    }

    /**
     * Control a train in the topology
     */
    @Override
    public Future<RpcResult<ControlTrainOutput>> controlTrain(ControlTrainInput input) {
        ControlTrainOutput output =  choochooTrainManager.controlTrain(input);
        return RpcResultBuilder.success(output).buildFuture();
    }
}
