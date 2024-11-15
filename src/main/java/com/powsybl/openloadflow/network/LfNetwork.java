/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Stopwatch;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.graph.GraphConnectivity;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.anarres.graphviz.builder.GraphVizGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.powsybl.openloadflow.util.Markers.PERFORMANCE_MARKER;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LfNetwork extends AbstractPropertyBag implements PropertyBag {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfNetwork.class);

    private static final SlackBusSelector SLACK_BUS_SELECTOR_FALLBACK = new MostMeshedSlackBusSelector();

    private final int numCC;

    private final int numSC;

    private final SlackBusSelector slackBusSelector;

    private final ReferenceBusSelector referenceBusSelector;

    private final int maxSlackBusCount;

    private final Map<String, LfBus> busesById = new LinkedHashMap<>();

    private final List<LfBus> busesByIndex = new ArrayList<>();

    private LfBus referenceBus;

    private List<LfBus> slackBuses;

    private Set<LfBus> excludedSlackBuses = Collections.emptySet();

    private LfGenerator referenceGenerator;

    private final List<LfBranch> branches = new ArrayList<>();

    private final Map<String, LfBranch> branchesById = new HashMap<>();

    private int shuntCount = 0;

    private final List<LfShunt> shuntsByIndex = new ArrayList<>();

    private final Map<String, LfShunt> shuntsById = new HashMap<>();

    private final Map<String, LfGenerator> generatorsById = new HashMap<>();

    private final Map<String, LfLoad> loadsById = new HashMap<>();

    private final Map<String, LfArea> areasById = new HashMap<>();

    private final List<LfHvdc> hvdcs = new ArrayList<>();

    private final Map<String, LfHvdc> hvdcsById = new HashMap<>();

    private final List<LfNetworkListener> listeners = new ArrayList<>();

    private Validity validity = Validity.VALID;

    private final GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory;

    private GraphConnectivity<LfBus, LfBranch> connectivity;

    private final Map<LoadFlowModel, Set<LfZeroImpedanceNetwork>> zeroImpedanceNetworksByModel = new EnumMap<>(LoadFlowModel.class);

    private ReportNode reportNode;

    private final List<LfSecondaryVoltageControl> secondaryVoltageControls = new ArrayList<>();

    private final List<LfVoltageAngleLimit> voltageAngleLimits = new ArrayList<>();

    public enum Validity {
        VALID("Valid"),
        INVALID_NO_GENERATOR("Network has no generator"),
        INVALID_NO_GENERATOR_VOLTAGE_CONTROL("Network has no generator with voltage control enabled");

        private final String description;

        Validity(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return this.description;
        }
    }

    public static class LfVoltageAngleLimit {
        private final String id;
        private final LfBus from;
        private final LfBus to;
        private final double highValue;
        private final double lowValue;

        public LfVoltageAngleLimit(String id, LfBus from, LfBus to, double highValue, double lowValue) {
            this.id = Objects.requireNonNull(id);
            this.from = Objects.requireNonNull(from);
            this.to = Objects.requireNonNull(to);
            this.highValue = highValue;
            this.lowValue = lowValue;
        }

        public String getId() {
            return id;
        }

        public LfBus getFrom() {
            return from;
        }

        public LfBus getTo() {
            return to;
        }

        public double getHighValue() {
            return highValue;
        }

        public double getLowValue() {
            return lowValue;
        }
    }

    protected final List<LfOverloadManagementSystem> overloadManagementSystems = new ArrayList<>();

    public LfNetwork(int numCC, int numSC, SlackBusSelector slackBusSelector, int maxSlackBusCount,
                     GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory, ReferenceBusSelector referenceBusSelector, ReportNode reportNode) {
        this.numCC = numCC;
        this.numSC = numSC;
        this.slackBusSelector = Objects.requireNonNull(slackBusSelector);
        this.maxSlackBusCount = maxSlackBusCount;
        this.connectivityFactory = Objects.requireNonNull(connectivityFactory);
        this.referenceBusSelector = referenceBusSelector;
        this.reportNode = Objects.requireNonNull(reportNode);
    }

    public LfNetwork(int numCC, int numSC, SlackBusSelector slackBusSelector, int maxSlackBusCount,
                     GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory, ReferenceBusSelector referenceBusSelector) {
        this(numCC, numSC, slackBusSelector, maxSlackBusCount, connectivityFactory, referenceBusSelector, ReportNode.NO_OP);
    }

    public int getNumCC() {
        return numCC;
    }

    public int getNumSC() {
        return numSC;
    }

    public ReportNode getReportNode() {
        return reportNode;
    }

    public void setReportNode(ReportNode reportNode) {
        this.reportNode = Objects.requireNonNull(reportNode);
    }

    public LfElement getElement(ElementType elementType, int num) {
        return switch (elementType) {
            case BUS -> getBus(num);
            case BRANCH -> getBranch(num);
            case SHUNT_COMPENSATOR -> getShunt(num);
            case HVDC -> getHvdc(num);
        };
    }

    private void invalidateSlackAndReference() {
        if (slackBuses != null) {
            for (var slackBus : slackBuses) {
                slackBus.setSlack(false);
            }
        }
        slackBuses = null;
        if (referenceBus != null) {
            referenceBus.setReference(false);
        }
        referenceBus = null;
        if (referenceGenerator != null) {
            referenceGenerator.setReference(false);
        }
        referenceGenerator = null;
    }

    public void updateSlackBusesAndReferenceBus() {
        if (slackBuses == null && referenceBus == null) {
            List<LfBus> selectableBuses =
                excludedSlackBuses.isEmpty() ? busesByIndex :
                    busesByIndex.stream().filter(bus -> !excludedSlackBuses.contains(bus)).toList();
            SelectedSlackBus selectedSlackBus = slackBusSelector.select(selectableBuses, maxSlackBusCount);
            slackBuses = selectedSlackBus.getBuses();
            if (slackBuses.isEmpty()) { // ultimate fallback
                selectedSlackBus = SLACK_BUS_SELECTOR_FALLBACK.select(selectableBuses, maxSlackBusCount);
                if (selectedSlackBus.getBuses().isEmpty()) {
                    throw new PowsyblException("No slack bus could be selected");
                }
                slackBuses = selectedSlackBus.getBuses();
            }
            LOGGER.info("Network {}, slack buses are {} (method='{}')", this, slackBuses, selectedSlackBus.getSelectionMethod());
            for (var slackBus : slackBuses) {
                slackBus.setSlack(true);
            }
            // reference bus must be selected after slack bus, because of ReferenceBusFirstSlackSelector implementation requiring slackBuses
            SelectedReferenceBus selectedReferenceBus = referenceBusSelector.select(this);
            referenceBus = selectedReferenceBus.getLfBus();
            LOGGER.info("Network {}, reference bus is {} (method='{}')", this, referenceBus, selectedReferenceBus.getSelectionMethod());
            referenceBus.setReference(true);
            if (selectedReferenceBus instanceof SelectedGeneratorReferenceBus generatorReferenceBus) {
                referenceGenerator = generatorReferenceBus.getLfGenerator();
                LOGGER.info("Network {}, reference generator is {}", this, referenceGenerator.getId());
                referenceGenerator.setReference(true);
            }
            if (connectivity != null) {
                connectivity.setMainComponentVertex(slackBuses.get(0));
            }
        }
    }

    private void invalidateZeroImpedanceNetworks() {
        zeroImpedanceNetworksByModel.clear();
    }

    public void addBranch(LfBranch branch) {
        Objects.requireNonNull(branch);
        branch.setNum(branches.size());
        branches.add(branch);
        branchesById.put(branch.getId(), branch);
        invalidateSlackAndReference();
        connectivity = null;
        invalidateZeroImpedanceNetworks();

        // create bus -> branches link
        if (branch.getBus1() != null) {
            branch.getBus1().addBranch(branch);
        }
        if (branch.getBus2() != null) {
            branch.getBus2().addBranch(branch);
        }
    }

    public List<LfBranch> getBranches() {
        return branches;
    }

    public LfBranch getBranch(int num) {
        return branches.get(num);
    }

    public LfBranch getBranchById(String branchId) {
        Objects.requireNonNull(branchId);
        return branchesById.get(branchId);
    }

    private void addShunt(LfShunt shunt) {
        shunt.setNum(shuntCount++);
        shuntsByIndex.add(shunt);
        shunt.getOriginalIds().forEach(id -> shuntsById.put(id, shunt));
    }

    public void addBus(LfBus bus) {
        Objects.requireNonNull(bus);
        bus.setNum(busesByIndex.size());
        busesByIndex.add(bus);
        busesById.put(bus.getId(), bus);
        invalidateSlackAndReference();
        connectivity = null;

        bus.getShunt().ifPresent(this::addShunt);
        bus.getControllerShunt().ifPresent(this::addShunt);
        bus.getSvcShunt().ifPresent(this::addShunt);
        bus.getGenerators().forEach(gen -> generatorsById.put(gen.getId(), gen));
        bus.getLoads().forEach(load -> load.getOriginalIds().forEach(id -> loadsById.put(id, load)));
    }

    public void addArea(LfArea area) {
        Objects.requireNonNull(area);
        areasById.put(area.getId(), area);
    }

    public List<LfBus> getBuses() {
        return busesByIndex;
    }

    public LfBus getBusById(String id) {
        Objects.requireNonNull(id);
        return busesById.get(id);
    }

    public LfBus getBus(int num) {
        return busesByIndex.get(num);
    }

    public LfBus getReferenceBus() {
        updateSlackBusesAndReferenceBus();
        return referenceBus;
    }

    public LfBus getSlackBus() {
        return getSlackBuses().get(0);
    }

    public List<LfBus> getSlackBuses() {
        updateSlackBusesAndReferenceBus();
        return slackBuses;
    }

    public Set<LfBus> getExcludedSlackBuses() {
        return excludedSlackBuses;
    }

    public void setExcludedSlackBuses(Set<LfBus> excludedSlackBuses) {
        Objects.requireNonNull(excludedSlackBuses);
        if (!excludedSlackBuses.equals(this.excludedSlackBuses)) {
            this.excludedSlackBuses = excludedSlackBuses;
            invalidateSlackAndReference();
        }
    }

    public LfGenerator getReferenceGenerator() {
        updateSlackBusesAndReferenceBus();
        return referenceGenerator;
    }

    public List<LfShunt> getShunts() {
        return shuntsByIndex;
    }

    public LfShunt getShunt(int num) {
        return shuntsByIndex.get(num);
    }

    public LfShunt getShuntById(String id) {
        Objects.requireNonNull(id);
        return shuntsById.get(id);
    }

    public LfGenerator getGeneratorById(String id) {
        Objects.requireNonNull(id);
        return generatorsById.get(id);
    }

    public LfLoad getLoadById(String id) {
        Objects.requireNonNull(id);
        return loadsById.get(id);
    }

    public Stream<LfArea> getAreaStream() {
        return areasById.values().stream();
    }

    public boolean hasArea() {
        return !areasById.isEmpty();
    }

    public LfArea getAreaById(String id) {
        Objects.requireNonNull(id);
        return areasById.get(id);
    }

    public void addHvdc(LfHvdc hvdc) {
        Objects.requireNonNull(hvdc);
        hvdc.setNum(hvdcs.size());
        hvdcs.add(hvdc);
        hvdcsById.put(hvdc.getId(), hvdc);

        // create bus -> branches link
        if (hvdc.getBus1() != null) {
            hvdc.getBus1().addHvdc(hvdc);
        }
        if (hvdc.getBus2() != null) {
            hvdc.getBus2().addHvdc(hvdc);
        }
    }

    public List<LfHvdc> getHvdcs() {
        return hvdcs;
    }

    public LfHvdc getHvdc(int num) {
        return hvdcs.get(num);
    }

    public LfHvdc getHvdcById(String id) {
        Objects.requireNonNull(id);
        return hvdcsById.get(id);
    }

    public void updateState(LfNetworkStateUpdateParameters parameters) {
        Stopwatch stopwatch = Stopwatch.createStarted();

        LfNetworkUpdateReport updateReport = new LfNetworkUpdateReport();

        for (LfBus bus : busesById.values()) {
            bus.updateState(parameters);
            for (LfGenerator generator : bus.getGenerators()) {
                generator.updateState(parameters);
            }
            bus.getShunt().ifPresent(shunt -> shunt.updateState(parameters));
            bus.getControllerShunt().ifPresent(shunt -> shunt.updateState(parameters));
        }
        branches.forEach(branch -> branch.updateState(parameters, updateReport));
        hvdcs.forEach(LfHvdc::updateState);

        if (updateReport.closedSwitchCount + updateReport.openedSwitchCount > 0) {
            LOGGER.debug("Switches status update: {} closed and {} opened", updateReport.closedSwitchCount, updateReport.openedSwitchCount);
        }
        if (updateReport.connectedBranchSide1Count + updateReport.disconnectedBranchSide1Count
                + updateReport.connectedBranchSide2Count + updateReport.disconnectedBranchSide2Count > 0) {
            LOGGER.debug("Branches connection status update: {} connected side 1, {} disconnected side1, {} connected side 2, {} disconnected side 2",
                    updateReport.connectedBranchSide1Count, updateReport.disconnectedBranchSide1Count, updateReport.connectedBranchSide2Count, updateReport.disconnectedBranchSide2Count);
        }

        stopwatch.stop();
        LOGGER.debug(PERFORMANCE_MARKER, "Network {}, IIDM network updated in {} ms", this, stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

    public void writeJson(Path file) {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeJson(writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeJson(LfBus bus, JsonGenerator jsonGenerator) throws IOException {
        jsonGenerator.writeStringField("id", bus.getId());
        jsonGenerator.writeNumberField("num", bus.getNum());
        if (bus.getGenerationTargetQ() != 0) {
            jsonGenerator.writeNumberField("generationTargetQ", bus.getGenerationTargetQ());
        }
        if (bus.getLoadTargetP() != 0) {
            jsonGenerator.writeNumberField("loadTargetP", bus.getLoadTargetP());
        }
        if (bus.getLoadTargetQ() != 0) {
            jsonGenerator.writeNumberField("loadTargetQ", bus.getLoadTargetQ());
        }
        bus.getGeneratorVoltageControl().ifPresent(vc -> {
            if (bus.isGeneratorVoltageControlEnabled()) {
                try {
                    if (vc.getControlledBus() != bus) {
                        jsonGenerator.writeNumberField("remoteControlTargetBus", vc.getControlledBus().getNum());
                    }
                    jsonGenerator.writeNumberField("targetV", vc.getTargetValue());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        if (!Double.isNaN(bus.getV())) {
            jsonGenerator.writeNumberField("v", bus.getV());
        }
        if (!Double.isNaN(bus.getAngle())) {
            jsonGenerator.writeNumberField("angle", bus.getAngle());
        }
    }

    private void writeJson(LfBranch branch, JsonGenerator jsonGenerator) throws IOException {
        jsonGenerator.writeStringField("id", branch.getId());
        jsonGenerator.writeNumberField("num", branch.getNum());
        LfBus bus1 = branch.getBus1();
        LfBus bus2 = branch.getBus2();
        if (bus1 != null) {
            jsonGenerator.writeNumberField("num1", bus1.getNum());
        }
        if (bus2 != null) {
            jsonGenerator.writeNumberField("num2", bus2.getNum());
        }
        PiModel piModel = branch.getPiModel();
        jsonGenerator.writeNumberField("r", piModel.getR());
        jsonGenerator.writeNumberField("x", piModel.getX());
        if (piModel.getG1() != 0) {
            jsonGenerator.writeNumberField("g1", piModel.getG1());
        }
        if (piModel.getG2() != 0) {
            jsonGenerator.writeNumberField("g2", piModel.getG2());
        }
        if (piModel.getB1() != 0) {
            jsonGenerator.writeNumberField("b1", piModel.getB1());
        }
        if (piModel.getB2() != 0) {
            jsonGenerator.writeNumberField("b2", piModel.getB2());
        }
        if (piModel.getR1() != 1) {
            jsonGenerator.writeNumberField("r1", piModel.getR1());
        }
        if (piModel.getA1() != 0) {
            jsonGenerator.writeNumberField("a1", piModel.getA1());
        }
        branch.getPhaseControl().filter(dpc -> branch.isPhaseController()).ifPresent(dpc -> {
            try {
                jsonGenerator.writeFieldName("discretePhaseControl");
                jsonGenerator.writeStartObject();
                jsonGenerator.writeStringField("controller", dpc.getControllerBranch().getId());
                jsonGenerator.writeStringField("controlled", dpc.getControlledBranch().getId());
                jsonGenerator.writeStringField("mode", dpc.getMode().name());
                jsonGenerator.writeStringField("unit", dpc.getUnit().name());
                jsonGenerator.writeStringField("controlledSide", dpc.getControlledSide().name());
                jsonGenerator.writeNumberField("targetValue", dpc.getTargetValue());
                jsonGenerator.writeEndObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void writeJson(LfShunt shunt, JsonGenerator jsonGenerator) throws IOException {
        jsonGenerator.writeStringField("id", shunt.getId());
        jsonGenerator.writeNumberField("num", shunt.getNum());
        jsonGenerator.writeNumberField("b", shunt.getB());
    }

    private void writeJson(LfGenerator generator, JsonGenerator jsonGenerator) throws IOException {
        jsonGenerator.writeStringField("id", generator.getId());
        jsonGenerator.writeNumberField("targetP", generator.getTargetP());
        if (!Double.isNaN(generator.getTargetQ())) {
            jsonGenerator.writeNumberField("targetQ", generator.getTargetQ());
        }
        jsonGenerator.writeBooleanField("voltageControl", generator.getGeneratorControlType() == LfGenerator.GeneratorControlType.VOLTAGE);
        jsonGenerator.writeNumberField("minP", generator.getMinP());
        jsonGenerator.writeNumberField("maxP", generator.getMaxP());
        jsonGenerator.writeNumberField("minTargetP", generator.getMinTargetP());
        jsonGenerator.writeNumberField("maxTargetP", generator.getMaxTargetP());
    }

    public void writeJson(Writer writer) {
        Objects.requireNonNull(writer);
        updateSlackBusesAndReferenceBus();
        try (JsonGenerator jsonGenerator = new JsonFactory()
                .createGenerator(writer)
                .useDefaultPrettyPrinter()) {
            jsonGenerator.writeStartObject();

            jsonGenerator.writeFieldName("buses");
            jsonGenerator.writeStartArray();
            List<LfBus> sortedBuses = busesById.values().stream().sorted(Comparator.comparing(LfBus::getId)).toList();
            for (LfBus bus : sortedBuses) {
                jsonGenerator.writeStartObject();

                writeJson(bus, jsonGenerator);

                bus.getShunt().ifPresent(shunt -> {
                    try {
                        jsonGenerator.writeFieldName("shunt");
                        jsonGenerator.writeStartObject();

                        writeJson(shunt, jsonGenerator);

                        jsonGenerator.writeEndObject();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

                List<LfGenerator> sortedGenerators = bus.getGenerators().stream().sorted(Comparator.comparing(LfGenerator::getId)).toList();
                if (!sortedGenerators.isEmpty()) {
                    jsonGenerator.writeFieldName("generators");
                    jsonGenerator.writeStartArray();
                    for (LfGenerator generator : sortedGenerators) {
                        jsonGenerator.writeStartObject();

                        writeJson(generator, jsonGenerator);

                        jsonGenerator.writeEndObject();
                    }
                    jsonGenerator.writeEndArray();
                }

                jsonGenerator.writeEndObject();
            }
            jsonGenerator.writeEndArray();

            jsonGenerator.writeFieldName("branches");
            jsonGenerator.writeStartArray();
            List<LfBranch> sortedBranches = branches.stream().sorted(Comparator.comparing(LfBranch::getId)).toList();
            for (LfBranch branch : sortedBranches) {
                jsonGenerator.writeStartObject();

                writeJson(branch, jsonGenerator);

                jsonGenerator.writeEndObject();
            }
            jsonGenerator.writeEndArray();

            jsonGenerator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void reportSize(ReportNode reportNode) {
        Reports.reportNetworkSize(reportNode, busesById.values().size(), branches.size());
        LOGGER.info("Network {} has {} buses and {} branches",
            this, busesById.values().size(), branches.size());
    }

    public void reportBalance(ReportNode reportNode) {
        double activeGeneration = 0;
        double reactiveGeneration = 0;
        double activeLoad = 0;
        double reactiveLoad = 0;
        for (LfBus b : busesById.values()) {
            activeGeneration += b.getGenerationTargetP() * PerUnit.SB;
            reactiveGeneration += b.getGenerationTargetQ() * PerUnit.SB;
            activeLoad += b.getLoadTargetP() * PerUnit.SB;
            reactiveLoad += b.getLoadTargetQ() * PerUnit.SB;
        }

        Reports.reportNetworkBalance(reportNode, activeGeneration, activeLoad, reactiveGeneration, reactiveLoad);
        LOGGER.info("Network {} balance: active generation={} MW, active load={} MW, reactive generation={} MVar, reactive load={} MVar",
            this, activeGeneration, activeLoad, reactiveGeneration, reactiveLoad);
    }

    public void fix(boolean minImpedance, double lowImpedanceThreshold) {
        if (minImpedance) {
            for (LfBranch branch : branches) {
                branch.setMinZ(lowImpedanceThreshold);
            }
        } else {
            // zero impedance phase shifter controller or controlled branch is not supported
            branches.stream()
                    .filter(b -> b.isPhaseController() || b.isPhaseControlled()
                            || b.isTransformerReactivePowerController() || b.isTransformerReactivePowerControlled()
                            || b.getGeneratorReactivePowerControl().isPresent())
                    .forEach(branch -> branch.setMinZ(lowImpedanceThreshold));
        }
    }

    private void validateBuses(LoadFlowModel loadFlowModel, ReportNode reportNode) {
        // DC or AC, if no generator, network is dead
        boolean hasAtLeastOneBusGenerator = false;
        for (LfBus bus : busesByIndex) {
            if (!bus.getGenerators().isEmpty()) {
                hasAtLeastOneBusGenerator = true;
                break;
            }
        }
        if (!hasAtLeastOneBusGenerator) {
            // we don't report because this is too much on real networks
            LOGGER.debug("Network {} has no generator and will be considered dead", this);
            validity = Validity.INVALID_NO_GENERATOR;
            return;
        }
        // AC requires at least one bus under voltage control
        if (loadFlowModel == LoadFlowModel.AC) {
            boolean hasAtLeastOneBusGeneratorVoltageControlEnabled = false;
            for (LfBus bus : busesByIndex) {
                if (bus.isGeneratorVoltageControlEnabled()) {
                    hasAtLeastOneBusGeneratorVoltageControlEnabled = true;
                    break;
                }
            }
            if (!hasAtLeastOneBusGeneratorVoltageControlEnabled) {
                LOGGER.error("Network {} must have at least one bus with generator voltage control enabled", this);
                if (reportNode != null) {
                    Reports.reportNetworkMustHaveAtLeastOneBusGeneratorVoltageControlEnabled(reportNode);
                }
                validity = Validity.INVALID_NO_GENERATOR_VOLTAGE_CONTROL;
            }
        }
    }

    public void validate(LoadFlowModel loadFlowModel, ReportNode reportNode) {
        validity = Validity.VALID;
        validateBuses(loadFlowModel, reportNode);
    }

    public static <T> List<LfNetwork> load(T network, LfNetworkLoader<T> networkLoader, SlackBusSelector slackBusSelector) {
        return load(network, networkLoader, new LfNetworkParameters().setSlackBusSelector(slackBusSelector), ReportNode.NO_OP);
    }

    public static <T> List<LfNetwork> load(T network, LfNetworkLoader<T> networkLoader, LfNetworkParameters parameters) {
        return load(network, networkLoader, parameters, ReportNode.NO_OP);
    }

    public static <T> List<LfNetwork> load(T network, LfNetworkLoader<T> networkLoader, LfNetworkParameters parameters, ReportNode reportNode) {
        return load(network, networkLoader, new LfTopoConfig(), parameters, reportNode);
    }

    public static <T> List<LfNetwork> load(T network, LfNetworkLoader<T> networkLoader, LfTopoConfig topoConfig, LfNetworkParameters parameters, ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(networkLoader);
        Objects.requireNonNull(parameters);
        List<LfNetwork> lfNetworks = networkLoader.load(network, topoConfig, parameters, reportNode);
        int deadComponentsCount = 0;
        for (LfNetwork lfNetwork : lfNetworks) {
            ReportNode networkReport = Reports.createNetworkInfoReporter(lfNetwork.getReportNode());
            lfNetwork.fix(parameters.isMinImpedance(), parameters.getLowImpedanceThreshold());
            lfNetwork.validate(parameters.getLoadFlowModel(), networkReport);
            switch (lfNetwork.getValidity()) {
                case VALID -> {
                    lfNetwork.reportSize(networkReport);
                    lfNetwork.reportBalance(networkReport);
                    Reports.reportAngleReferenceBusAndSlackBuses(networkReport, lfNetwork.getReferenceBus().getId(), lfNetwork.getSlackBuses().stream().map(LfBus::getId).toList());
                    lfNetwork.setReportNode(Reports.includeLfNetworkReportNode(reportNode, lfNetwork.getReportNode()));
                }
                case INVALID_NO_GENERATOR_VOLTAGE_CONTROL -> {
                    LOGGER.info("Network {} is invalid, no calculation will be done", lfNetwork);
                    // we want to report this
                    lfNetwork.setReportNode(Reports.includeLfNetworkReportNode(reportNode, lfNetwork.getReportNode()));
                }
                case INVALID_NO_GENERATOR -> deadComponentsCount++; // will be reported later on altogether
            }
        }
        if (deadComponentsCount > 0) {
            Reports.reportComponentsWithoutGenerators(reportNode, deadComponentsCount);
            LOGGER.info("No calculation will be done on {} network(s) that have no generators", deadComponentsCount);
        }
        return lfNetworks;
    }

    public void updateZeroImpedanceCache(LoadFlowModel loadFlowModel) {
        zeroImpedanceNetworksByModel.computeIfAbsent(loadFlowModel, m -> LfZeroImpedanceNetwork.create(this, loadFlowModel));
    }

    public Set<LfZeroImpedanceNetwork> getZeroImpedanceNetworks(LoadFlowModel loadFlowModel) {
        updateZeroImpedanceCache(loadFlowModel);
        return zeroImpedanceNetworksByModel.get(loadFlowModel);
    }

    public GraphConnectivity<LfBus, LfBranch> getConnectivity() {
        if (connectivity == null) {
            connectivity = Objects.requireNonNull(connectivityFactory.create());
            getBuses().forEach(connectivity::addVertex);
            getBranches().stream()
                    .filter(b -> b.getBus1() != null && b.getBus2() != null)
                    .forEach(b -> connectivity.addEdge(b.getBus1(), b.getBus2(), b));
            connectivity.setMainComponentVertex(getSlackBuses().get(0));
            // this is necessary to create a first temporary changes level in order to allow
            // some outer loop to change permanently the connectivity (with automation systems for instance)
            // this one will never be reverted
            if (connectivity.supportTemporaryChangesNesting()) {
                connectivity.startTemporaryChanges();
            }
        }
        return connectivity;
    }

    public void addListener(LfNetworkListener listener) {
        listeners.add(listener);
    }

    public void removeListener(LfNetworkListener listener) {
        listeners.remove(listener);
    }

    public List<LfNetworkListener> getListeners() {
        return listeners;
    }

    public Validity getValidity() {
        return validity;
    }

    /**
     * Disable transformer voltage control when there is no generator controlling voltage on the connected component
     * that belong to the not controlled side of the transformer. Note that branches in contingency are not taken into
     * account.
     */
    public void fixTransformerVoltageControls() {
        List<LfBranch> controllerBranches = new ArrayList<>(1);
        getConnectivity().startTemporaryChanges();
        for (LfBranch branch : branches) {
            if (!branch.isDisabled() && branch.isVoltageController() && branch.isVoltageControlEnabled()) {
                controllerBranches.add(branch);
            }
            if (branch.isDisabled() && branch.getBus1() != null && branch.getBus2() != null) {
                // apply contingency (in case we are inside a security analysis)
                getConnectivity().removeEdge(branch);
            }
        }
        for (LfBranch branch : controllerBranches) {
            getConnectivity().removeEdge(branch);
        }
        int disabledTransformerCount = 0;
        Map<Integer, Boolean> componentNoPVBusesMap = new HashMap<>();
        for (LfBranch branch : controllerBranches) {
            var voltageControl = branch.getVoltageControl().orElseThrow();
            LfBus notControlledSide;
            if (voltageControl.getControlledBus() == branch.getBus1()) {
                notControlledSide = branch.getBus2();
            } else if (voltageControl.getControlledBus() == branch.getBus2()) {
                notControlledSide = branch.getBus1();
            } else {
                continue;
            }
            boolean noPvBusesInComponent = componentNoPVBusesMap.computeIfAbsent(getConnectivity().getComponentNumber(notControlledSide),
                k -> getConnectivity().getConnectedComponent(notControlledSide).stream()
                        .noneMatch(bus -> bus.isGeneratorVoltageControlled() && bus.isGeneratorVoltageControlEnabled()));
            if (noPvBusesInComponent) {
                branch.setVoltageControlEnabled(false);
                LOGGER.trace("Transformer {} voltage control has been disabled because no PV buses on not controlled side connected component",
                        branch.getId());
                disabledTransformerCount++;
            }
        }
        getConnectivity().undoTemporaryChanges();
        if (disabledTransformerCount > 0) {
            LOGGER.warn("{} transformer voltage controls have been disabled because no PV buses on not controlled side connected component",
                    disabledTransformerCount);
        }
    }

    public void writeGraphViz(Path file, LoadFlowModel loadFlowModel) {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeGraphViz(writer, loadFlowModel);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeGraphViz(Writer writer, LoadFlowModel loadFlowModel) {
        try {
            GraphVizGraph gvGraph = new GraphVizGraphBuilder(this).build(loadFlowModel);
            gvGraph.writeTo(writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String getId() {
        return "{CC" + numCC + " SC" + numSC + '}';
    }

    public void addSecondaryVoltageControl(LfSecondaryVoltageControl secondaryVoltageControl) {
        secondaryVoltageControls.add(Objects.requireNonNull(secondaryVoltageControl));
    }

    public List<LfSecondaryVoltageControl> getSecondaryVoltageControls() {
        return secondaryVoltageControls;
    }

    public Optional<LfSecondaryVoltageControl> getSecondaryVoltageControl(String controlZoneName) {
        Objects.requireNonNull(controlZoneName);
        return secondaryVoltageControls.stream()
                .filter(lfSvc -> lfSvc.getZoneName().equals(controlZoneName))
                .findFirst();
    }

    private static boolean filterSecondaryVoltageControl(LfSecondaryVoltageControl secondaryVoltageControl) {
        return !secondaryVoltageControl.getPilotBus().isDisabled();
    }

    public List<LfSecondaryVoltageControl> getEnabledSecondaryVoltageControls() {
        return secondaryVoltageControls.stream()
                .filter(LfNetwork::filterSecondaryVoltageControl)
                .toList();
    }

    public void addVoltageAngleLimit(LfVoltageAngleLimit limit) {
        voltageAngleLimits.add(Objects.requireNonNull(limit));
    }

    public List<LfVoltageAngleLimit> getVoltageAngleLimits() {
        return voltageAngleLimits;
    }

    @SuppressWarnings("unchecked")
    public <E extends LfElement> List<E> getControllerElements(VoltageControl.Type type) {
        return busesByIndex.stream()
                .filter(bus -> bus.isVoltageControlled(type))
                .filter(bus -> bus.getVoltageControl(type).orElseThrow().getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                .filter(bus -> bus.getVoltageControl(type).orElseThrow().isVisible())
                .flatMap(bus -> bus.getVoltageControl(type).orElseThrow().getMergedControllerElements().stream())
                .filter(Predicate.not(LfElement::isDisabled))
                .map(element -> (E) element)
                .toList();
    }

    public List<LfBus> getControlledBuses(VoltageControl.Type type) {
        return busesByIndex.stream()
                .filter(bus -> bus.isVoltageControlled(type))
                .filter(bus -> bus.getVoltageControl(type).orElseThrow().getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                .filter(bus -> bus.getVoltageControl(type).orElseThrow().isVisible())
                .toList();
    }

    public void addOverloadManagementSystem(LfOverloadManagementSystem overloadManagementSystem) {
        overloadManagementSystems.add(Objects.requireNonNull(overloadManagementSystem));
    }

    public List<LfOverloadManagementSystem> getOverloadManagementSystems() {
        return overloadManagementSystems;
    }

    public void setGeneratorsInitialTargetPToTargetP() {
        getBuses().stream().flatMap(b -> b.getGenerators().stream()).forEach(LfGenerator::setInitialTargetPToTargetP);
    }

    @Override
    public String toString() {
        return getId();
    }
}
