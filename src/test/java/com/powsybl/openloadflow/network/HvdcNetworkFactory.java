/*
 * Copyright (c) 2021-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;

/**
 * @author Gaël Macherel {@literal <gael.macherel@artelys.com>}
 */
public class HvdcNetworkFactory extends AbstractLoadFlowNetworkFactory {

    /**
     * VSC test case.
     * <pre>
     * g1       ld2               ld3
     * |         |                 |
     * b1 ------- b2-cs2--------cs3-b3
     * l12          hvdc23
     * </pre>
     *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
     */
    public static Network createVsc() {
        Network network = Network.create("vsc", "test");

        Substation s1 = network.newSubstation()
                               .setId("S1")
                               .add();
        VoltageLevel vl1 = s1.newVoltageLevel()
                             .setId("vl1")
                             .setNominalV(400)
                             .setTopologyKind(TopologyKind.BUS_BREAKER)
                             .add();
        vl1.getBusBreakerView().newBus()
           .setId("b1")
           .add();
        vl1.newGenerator()
           .setId("g1")
           .setConnectableBus("b1")
           .setBus("b1")
           .setTargetP(102.56)
           .setTargetV(390)
           .setMinP(0)
           .setMaxP(500)
           .setVoltageRegulatorOn(true)
            .add();

        Substation s2 = network.newSubstation()
                               .setId("S2")
                               .add();
        VoltageLevel vl2 = s2.newVoltageLevel()
                             .setId("vl2")
                             .setNominalV(400)
                             .setTopologyKind(TopologyKind.BUS_BREAKER)
                             .add();
        vl2.getBusBreakerView().newBus()
           .setId("b2")
           .add();
        vl2.newLoad()
           .setId("ld2")
           .setConnectableBus("b2")
           .setBus("b2")
           .setP0(50)
           .setQ0(10)
            .add();
        vl2.newVscConverterStation()
           .setId("cs2")
           .setConnectableBus("b2")
           .setBus("b2")
           .setVoltageRegulatorOn(true)
           .setVoltageSetpoint(385)
           .setReactivePowerSetpoint(100)
           .setLossFactor(1.1f)
            .add();

        Substation s3 = network.newSubstation()
                               .setId("S3")
                               .add();
        VoltageLevel vl3 = s3.newVoltageLevel()
                             .setId("vl3")
                             .setNominalV(400)
                             .setTopologyKind(TopologyKind.BUS_BREAKER)
                             .add();
        vl3.getBusBreakerView().newBus()
           .setId("b3")
           .add();
        vl3.newLoad()
           .setId("ld3")
           .setConnectableBus("b3")
           .setBus("b3")
           .setP0(50)
           .setQ0(10)
            .add();
        vl3.newVscConverterStation()
           .setId("cs3")
           .setConnectableBus("b3")
           .setBus("b3")
           .setVoltageRegulatorOn(true)
           .setVoltageSetpoint(383)
           .setReactivePowerSetpoint(100)
           .setLossFactor(0.2f)
            .add();

        network.newLine()
               .setId("l12")
               .setBus1("b1")
               .setBus2("b2")
               .setR(1)
               .setX(3)
               .add();

        network.newHvdcLine()
               .setId("hvdc23")
               .setConverterStationId1("cs2")
               .setConverterStationId2("cs3")
               .setNominalV(400)
               .setR(0.1)
               .setActivePowerSetpoint(50)
               .setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)
               .setMaxP(500)
               .add();

        return network;
    }

    /**
     * LCC test case.
     * <pre>
     *  g1       ld2               ld3
     *  |         |                 |
     * b1 ------- b2-cs2--------cs3-b3
     *      l12          hvdc23     |
     *                              g3
     * </pre>
     *
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
     */
    public static Network createLcc() {
        Network network = Network.create("lcc", "test");

        Substation s1 = network.newSubstation()
                               .setId("S1")
                               .add();

        VoltageLevel vl1 = s1.newVoltageLevel()
                             .setId("vl1")
                             .setNominalV(400)
                             .setTopologyKind(TopologyKind.BUS_BREAKER)
                             .add();
        vl1.getBusBreakerView().newBus()
           .setId("b1")
           .add();
        vl1.newGenerator()
           .setId("g1")
           .setConnectableBus("b1")
           .setBus("b1")
           .setTargetP(102.56)
           .setTargetV(390)
           .setMinP(0)
           .setMaxP(500)
           .setVoltageRegulatorOn(true)
            .add();

        Substation s2 = network.newSubstation()
                               .setId("S2")
                               .add();
        VoltageLevel vl2 = s2.newVoltageLevel()
                             .setId("vl2")
                             .setNominalV(400)
                             .setTopologyKind(TopologyKind.BUS_BREAKER)
                             .add();
        vl2.getBusBreakerView().newBus()
           .setId("b2")
           .add();
        vl2.newLoad()
           .setId("ld2")
           .setConnectableBus("b2")
           .setBus("b2")
           .setP0(50)
           .setQ0(10)
            .add();
        vl2.newLccConverterStation()
           .setId("cs2")
           .setConnectableBus("b2")
           .setBus("b2")
           .setPowerFactor(0.8f)
           .setLossFactor(0.1f)
            .add();

        Substation s3 = network.newSubstation()
                               .setId("S3")
                               .add();
        VoltageLevel vl3 = s3.newVoltageLevel()
                             .setId("vl3")
                             .setNominalV(400)
                             .setTopologyKind(TopologyKind.BUS_BREAKER)
                             .add();
        vl3.getBusBreakerView().newBus()
           .setId("b3")
           .add();
        vl3.newLoad()
           .setId("ld3")
           .setConnectableBus("b3")
           .setBus("b3")
           .setP0(50)
           .setQ0(10)
            .add();
        vl3.newLccConverterStation()
           .setId("cs3")
           .setConnectableBus("b3")
           .setBus("b3")
           .setPowerFactor(0.8f)
           .setLossFactor(1.1f)
            .add();
        vl3.newGenerator()
           .setId("g3")
           .setConnectableBus("b3")
           .setBus("b3")
           .setTargetP(102.56)
           .setTargetV(380)
           .setMinP(0)
           .setMaxP(500)
           .setVoltageRegulatorOn(true)
            .add();

        network.newLine()
               .setId("l12")
               .setBus1("b1")
               .setBus2("b2")
               .setR(1)
               .setX(3)
               .add();

        network.newHvdcLine()
               .setId("hvdc23")
               .setConverterStationId1("cs2")
               .setConverterStationId2("cs3")
               .setNominalV(400)
               .setR(0.1)
               .setActivePowerSetpoint(50)
               .setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)
               .setMaxP(500)
               .add();

        return network;
    }

    /**
     * LCC test case with bigger components
     * <pre>
     *                       g1       ld2               ld3
     *                       |         |                 |
     * lots of buses ------ b1 ------- b2-cs2--------cs3-b3 ----- b4 ----- b5 ------ b6
     *                           l12          hvdc23     |         (transfo)
     *                                                  g3
     * </pre>
     *
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
     */
    public static Network createLccWithBiggerComponents() {
        Network network = createLcc();
        Bus b1 = network.getBusBreakerView().getBus("b1");
        Bus b3 = network.getBusBreakerView().getBus("b3");
        Bus b4 = createBus(network, "test_s", "b4", 400);
        Bus b5 = createBus(network, "test_s", "b5", 400);
        Bus b6 = createBus(network, "test_s", "b6", 400);
        createLine(network, b3, b4, "l34", 0.1f);
        createLine(network, b5, b6, "l56", 0.1f);
        TwoWindingsTransformer twt = createTransformer(network, "test_s", b4, b5, "l45", 0.1f, 1d);
        twt.newPhaseTapChanger().setTapPosition(0)
            .beginStep()
            .setX(0.1f)
            .setAlpha(1)
            .endStep()
            .add();

        createGenerator(b6, "g6", 1);

        for (int i = 0; i < 10; i++) {
            Bus b = createBus(network, "test_s", "additionnalbus_" + i, 400);
            createLine(network, b1, b, "additionnalline_" + i, 0.1f);
        }

        return network;
    }

    public static Network createLccWithBiggerComponentsAndAdditionalLine() {
        Network network = createLccWithBiggerComponents();

        createLine(network, network.getBusBreakerView().getBus("additionnalbus_0"), network.getBusBreakerView().getBus("additionnalbus_1"), "additionnalline_01", 0.1f);
        return network;
    }

    public static Network createLccWithBiggerComponentsAndAdditionalLine2() {
        Network network = createLccWithBiggerComponents();

        createBus(network, "test_s", "additionnalbus_10", 400);
        createLine(network, network.getBusBreakerView().getBus("additionnalbus_0"), network.getBusBreakerView().getBus("additionnalbus_10"), "additionnalline_10", 0.1f);
        return network;
    }

    /**
     * <pre>
     * b1 ----------+
     * |            |
     * b2 -------- b3 - cs3
     *              hvdc34
     * b5 -------- b4 - cs4
     * |            |
     * b6 ----------+
     * </pre>
     *
     * @return network
     */
    public static Network createTwoCcLinkedByAHvdc() {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1");
        Bus b2 = createBus(network, "b2");
        Bus b3 = createBus(network, "b3");
        Bus b4 = createBus(network, "b4");
        Bus b5 = createBus(network, "b5");
        Bus b6 = createBus(network, "b6");
        createLine(network, b1, b2, "l12", 0.1f);
        createLine(network, b1, b3, "l13", 0.1f);
        createLine(network, b2, b3, "l23", 0.1f);

        HvdcConverterStation cs3 = createLcc(b3, "cs3");
        HvdcConverterStation cs4 = createLcc(b4, "cs4");
        createHvdcLine(network, "hvdc34", cs3, cs4, 400, 0.1, 2);

        createLine(network, b4, b5, "l45", 0.1f);
        createLine(network, b4, b6, "l46", 0.1f);
        createLine(network, b5, b6, "l56", 0.1f);
        return network;
    }

    /**
     * <pre>
     *      Gen - b1 -- l12 -- b2 -- HVDC23-- b3 -- l34 -- b4 - Load
     *            |            |                           |
     *            |          s2 (open)                     |
     *            |            |                           |
     *            |---l12Bis --                            |
     *            |                                        |
     *            ---------------------l14------------------
     * </pre>
     * @return
     */
    public static Network createHvdcLinkedByTwoLinesAndSwitch() {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1", 400);
        Bus b2 = createBus(network, "b2", 400);
        Bus b2Bis = b2.getVoltageLevel().getBusBreakerView().newBus().setId("b2Bis").add();
        Bus b3 = createBus(network, "b3", 400);
        Bus b4 = createBus(network, "b4", 400);
        createGenerator(b1, "g1", 400, 400);
        createLine(network, b1, b2, "l12", 0.1f);
        createLine(network, b1, b2Bis, "l12Bis", 0.1f);
        createSwitch(network, b2, b2Bis, "s2").setOpen(true);
        VscConverterStation cs2 = createVsc(b2, "cs2", 400, 0);
        VscConverterStation cs3 = createVsc(b3, "cs3", 400, 0);
        createHvdcLine(network, "hvdc23", cs2, cs3, 400, 0.1, 200)
                .setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)
                .newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(200)
                .withEnabled(true)
                .add();
        createLine(network, b3, b4, "l34", 0.1f);
        createLine(network, b1, b4, "l14", 0.1f);
        createLoad(b4, "l4", 300, 0);
        return network;
    }

    /**
     * <pre>
     * b1 ----------+
     * |            |
     * b2 -------- b3 - cs3
     *              hvdc34
     * b5 -------- b4 - cs4
     * |            |
     * b6 ----------+
     * </pre>
     *
     * @return network
     */
    public static Network createTwoCcLinkedByAHvdcVsc() {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1");
        Bus b2 = createBus(network, "b2");
        Bus b3 = createBus(network, "b3");
        Bus b4 = createBus(network, "b4");
        Bus b5 = createBus(network, "b5");
        Bus b6 = createBus(network, "b6");
        createLine(network, b1, b2, "l12", 0.1f);
        createLine(network, b1, b3, "l13", 0.1f);
        createLine(network, b2, b3, "l23", 0.1f);

        HvdcConverterStation cs3 = createVsc(b3, "cs3", 1.2d, 0d);
        HvdcConverterStation cs4 = createVsc(b4, "cs4", 1.2d, 0d);
        createHvdcLine(network, "hvdc34", cs3, cs4, 400, 0.1, 2);

        createLine(network, b4, b5, "l45", 0.1f);
        createLine(network, b4, b6, "l46", 0.1f);
        createLine(network, b5, b6, "l56", 0.1f);
        return network;
    }

    /**
     * <pre>
     * b1 -----------
     * |            |
     * b2 ---twt-- b3 - cs3
     *              hvdc34
     * b5 -------- b4 - cs4
     * |            |
     * b6 ----------+
     * </pre>
     *
     * @return network
     */
    public static Network createTwoCcLinkedByAHvdcWithATransformer() {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1");
        Bus b2 = createBus(network, "test_s", "b2");
        Bus b3 = createBus(network, "test_s", "b3");
        Bus b4 = createBus(network, "b4");
        Bus b5 = createBus(network, "b5");
        Bus b6 = createBus(network, "b6");
        createLine(network, b1, b2, "l12", 0.1f);
        createLine(network, b1, b3, "l13", 0.1f);
        TwoWindingsTransformer twt = createTransformer(network, "test_s", b2, b3, "l23", 0.1f, 1d);
        twt.newPhaseTapChanger().setTapPosition(0)
            .beginStep()
            .setX(0.1f)
            .setAlpha(1)
            .endStep()
            .add();
        HvdcConverterStation cs3 = createLcc(b3, "cs3");
        HvdcConverterStation cs4 = createLcc(b4, "cs4");
        createHvdcLine(network, "hvdc34", cs3, cs4, 400, 0.1, 2);

        createLine(network, b4, b5, "l45", 0.1f);
        createLine(network, b4, b6, "l46", 0.1f);
        createLine(network, b5, b6, "l56", 0.1f);
        return network;
    }

    public static Network createTwoCcLinkedByAHvdcVscWithGenerators() {
        Network network = createTwoCcLinkedByAHvdcVsc();
        Bus b1 = network.getBusBreakerView().getBus("b1");
        Bus b2 = network.getBusBreakerView().getBus("b2");
        Bus b6 = network.getBusBreakerView().getBus("b6");
        createGenerator(b1, "g1", 1);
        createGenerator(b2, "g2", 1);
        createLoad(b2, "d2", 4);
        createGenerator(b6, "g6", 1);
        return network;
    }

    public static Network createTwoCcLinkedByAHvdcWithGenerators() {
        Network network = createTwoCcLinkedByAHvdc();
        Bus b1 = network.getBusBreakerView().getBus("b1");
        Bus b2 = network.getBusBreakerView().getBus("b2");
        Bus b6 = network.getBusBreakerView().getBus("b6");
        createGenerator(b1, "g1", 1);
        createGenerator(b2, "g2", 1);
        createLoad(b2, "d2", 4);
        createGenerator(b6, "g6", 1);
        return network;
    }

    /**
     * <pre>
     * b1 ----------+
     * |            |
     * b2 -------- b3 - cs3
     * |           hvdc34
     * b5 -------- b4 - cs4
     * |            |
     * b6 ----------+
     * </pre>
     *
     * @return network
     */
    public static Network createNetworkWithGenerators() {
        Network network = createTwoCcLinkedByAHvdcWithGenerators();
        Bus b2 = network.getBusBreakerView().getBus("b2");
        Bus b5 = network.getBusBreakerView().getBus("b5");
        createLine(network, b2, b5, "l25", 0.1f);
        createGenerator(b5, "g5", 1);
        return network;
    }

    public static Network createNetworkWithGenerators2() {
        Network network = createTwoCcLinkedByAHvdcVscWithGenerators();
        Bus b2 = network.getBusBreakerView().getBus("b2");
        Bus b5 = network.getBusBreakerView().getBus("b5");
        createLine(network, b2, b5, "l25", 0.1f);
        createGenerator(b5, "g5", 1);
        return network;
    }

    public static Network createNetworkWithTransformer() {
        Network network = createTwoCcLinkedByAHvdcWithATransformer();
        Bus b1 = network.getBusBreakerView().getBus("b1");
        Bus b2 = network.getBusBreakerView().getBus("b2");
        Bus b6 = network.getBusBreakerView().getBus("b6");
        createGenerator(b1, "g1", 1);
        createGenerator(b2, "g2", 1);
        createLoad(b2, "d2", 4);
        createGenerator(b6, "g6", 1);
        return network;
    }

    public static Network createLinkedNetworkWithTransformer() {
        Network network = createNetworkWithTransformer();
        Bus b2 = network.getBusBreakerView().getBus("b2");
        Bus b5 = network.getBusBreakerView().getBus("b5");
        createLine(network, b2, b5, "l25", 0.1f);
        createGenerator(b5, "g5", 1);
        return network;
    }

    /**
     * <pre>
     * b1 ----------+
     * |            |
     * b2 -------- b3 - cs3
     *              hvdc34
     * b5 -------- b4 - cs4
     * |            |
     * b6 ----------+
     * </pre>
     *
     * @return network
     */
    public static Network createWithHvdcInAcEmulation() {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1");
        Bus b2 = createBus(network, "b2");
        Bus b3 = createBus(network, "b3");
        Bus b4 = createBus(network, "b4");
        Bus b5 = createBus(network, "b5");
        Bus b6 = createBus(network, "b6");
        createLine(network, b1, b2, "l12", 0.1f);
        createLine(network, b1, b3, "l13", 0.1f);
        createLine(network, b2, b3, "l23", 0.1f);
        createLine(network, b2, b5, "l25", 0.05f);

        HvdcConverterStation cs3 = createVsc(b3, "cs3", 1.2d, 0d);
        HvdcConverterStation cs4 = createVsc(b4, "cs4", 1.2d, 0d);
        createHvdcLine(network, "hvdc34", cs3, cs4, 400, 0.1, 2);

        createLine(network, b4, b5, "l45", 0.1f);
        createLine(network, b4, b6, "l46", 0.1f);
        createLine(network, b5, b6, "l56", 0.1f);

        createGenerator(b1, "g1", 1);
        createGenerator(b5, "g5", 1);
        network.getGenerator("g1").setMaxP(5);
        network.getGenerator("g5").setMaxP(5);

        createLoad(b2, "d2", 4);
        return network;
    }

    /**
     *       ------------l12--------------
     *      |                            |
     * g1--b1-----vsc1--hvdc12--vsc2----b2--g2
     *     |                             |
     *     l1                            l2
     *
     *     Initially, g1 is on. g2 is off.
     * @return
     */
    public static Network createHvdcInAcEmulationInSymetricNetwork() {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1");
        Bus b2 = createBus(network, "b2");
        createGenerator(b1, "g1", 5).setMaxP(10);
        createGenerator(b2, "g2", 0).setMaxP(10);
        createLoad(b1, "l1", 3);
        createLoad(b2, "l2", 3);
        createLine(network, b1, b2, "l12", 0.1f);

        HvdcConverterStation cs1 = createVsc(b1, "cs1", 1.2d, 0d);
        HvdcConverterStation cs2 = createVsc(b2, "cs2", 1.2d, 0d);
        createHvdcLine(network, "hvdc12", cs1, cs2, 400, 0.1, 2)
                .newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(1)
                .withP0(0)
                .withEnabled(true)
                .add();
        return network;
    }

    public static Network createHvdcLinkedByTwoLinesAndSwitch(HvdcConverterStation.HvdcType type) {
        return createHvdcLinkedByTwoLinesAndSwitch(type, 180);
    }

    /**
     * <pre>
     *     g1 - b1 -- l12 -- b2 -- hvdc23 -- b3 -- l34 -- b4 - l4
     *          |            |                            |
     *          |           s2 (open)                     |
     *          |            |                            |
     *          |---l12Bis --                             |
     *          |                                         |
     *          ---------------------l14-------------------
     * </pre>
     * @return
     */
    public static Network createHvdcLinkedByTwoLinesAndSwitch(HvdcConverterStation.HvdcType type, float droopMWPerDeg) {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1", 400);
        Bus b2 = createBus(network, "b2", 400);
        Bus b2Bis = b2.getVoltageLevel().getBusBreakerView().newBus().setId("b2Bis").add();
        Bus b3 = createBus(network, "b3", 400);
        Bus b4 = createBus(network, "b4", 400);
        createGenerator(b1, "g1", 400, 400);
        createLine(network, b1, b2, "l12", 0.1f);
        createLine(network, b1, b2Bis, "l12Bis", 0.1f);
        createSwitch(network, b2, b2Bis, "s2").setOpen(true);
        HvdcConverterStation cs2 = switch (type) {
            case LCC -> createLcc(b2, "cs2");
            case VSC -> createVsc(b2, "cs2", 400, 0);
        };
        HvdcConverterStation cs3 = switch (type) {
            case LCC -> createLcc(b3, "cs3");
            case VSC -> createVsc(b3, "cs3", 400, 0);
        };
        createHvdcLine(network, "hvdc23", cs2, cs3, 400, 0.1, 200)
                .newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(droopMWPerDeg)
                .withP0(200)
                .withEnabled(true)
                .add();
        createLine(network, b3, b4, "l34", 0.1f);
        createLine(network, b1, b4, "l14", 0.1f);
        createLoad(b4, "l4", 300, 0);
        return network;
    }

    /**
     * <pre>
     *     g1 - b1           b2 -- hvdc23 -- b3 -- l34 -- b4 - l4
     *          |            |               |             |
     *          |           s2 (closed)      s3 (closed)   l45
     *          |            |               |            |
     *          C|---l12------                b7--l7       b5---l56-b6-g6
     *
     * </pre>
     * @return
     */
    public static Network createHvdcAndSwitch(HvdcConverterStation.HvdcType type) {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1", 400);
        Bus b2 = createBus(network, "b2", 400);
        Bus b2Bis = b2.getVoltageLevel().getBusBreakerView().newBus().setId("b2Bis").add();
        Bus b3 = createBus(network, "b3", 400);
        Bus b4 = createBus(network, "b4", 400);
        Bus b5 = createBus(network, "b5", 400);
        Bus b6 = createBus(network, "b6", 400);
        Bus b7 = b3.getVoltageLevel().getBusBreakerView().newBus().setId("b7").add();

        createGenerator(b1, "g1", 400, 400);
        createLine(network, b1, b2, "l12", 0.1f);
        createLine(network, b1, b2Bis, "l12Bis", 0.1f);
        createSwitch(network, b2, b2Bis, "s2").setOpen(false);
        createSwitch(network, b3, b7, "s3").setOpen(false);
        HvdcConverterStation cs2 = switch (type) {
            case LCC -> createLcc(b2, "cs2");
            case VSC -> createVsc(b2, "cs2", 400, 0);
        };
        HvdcConverterStation cs3 = switch (type) {
            case LCC -> createLcc(b3, "cs3");
            case VSC -> createVsc(b3, "cs3", 400, 0);
        };
        createHvdcLine(network, "hvdc23", cs2, cs3, 400, 0.1, 200)
                .newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(200)
                .withEnabled(true)
                .add();
        createLine(network, b3, b4, "l34", 0.1f);
        createLine(network, b4, b5, "l45", 0.1f);
        createLine(network, b5, b6, "l56", 0.1f);
        createLoad(b4, "l4", 300, 0);
        createLoad(b7, "l7", 50, 0);
        createGenerator(b6, "g6", 400, 400);
        return network;
    }

    /**
     * <pre>
     * b1 ----------+
     * |            |
     * b2 -------- b3 - cs3
     *              hvdc34
     *             b4 - g4
     * </pre>
     *
     * @return network
     */
    public static Network createWithHvdcAndGenerator() {
        Network network = Network.create("test", "code");
        Bus b1 = createBus(network, "b1");
        Bus b2 = createBus(network, "b2");
        Bus b3 = createBus(network, "b3");
        Bus b4 = createBus(network, "b4");
        createLine(network, b1, b2, "l12", 0.1f);
        createLine(network, b1, b3, "l13", 0.1f);
        createLine(network, b2, b3, "l23", 0.1f);
        HvdcConverterStation cs3 = createVsc(b3, "cs3", 1.2d, 0d);
        HvdcConverterStation cs4 = createVsc(b4, "cs4", 1.2d, 0d);
        createHvdcLine(network, "hvdc34", cs3, cs4, 400, 0.1, 2);
        createGenerator(b1, "g1", 1);
        createGenerator(b4, "g4", 2);
        network.getGenerator("g1").setMaxP(5);
        network.getGenerator("g4").setMaxP(5);
        createLoad(b2, "d2", 4);
        return network;
    }
}
