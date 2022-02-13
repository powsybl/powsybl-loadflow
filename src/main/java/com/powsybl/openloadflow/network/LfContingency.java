/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.util.PerUnit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class LfContingency {

    private final String id;

    private final int index;

    private final Set<LfBus> buses;

    private final Set<LfBranch> branches;

    private final Map<LfShunt, Double> shunts;

    private final Map<LfBus, PowerShift> loadBuses;

    private final Set<LfGenerator> generators;

    private double activePowerLoss = 0;

    public LfContingency(String id, int index, Set<LfBus> buses, Set<LfBranch> branches, Map<LfShunt, Double> shunts,
                         Map<LfBus, PowerShift> loadBuses, Set<LfGenerator> generators) {
        this.id = Objects.requireNonNull(id);
        this.index = index;
        this.buses = Objects.requireNonNull(buses);
        this.branches = Objects.requireNonNull(branches);
        this.shunts = Objects.requireNonNull(shunts);
        this.loadBuses = Objects.requireNonNull(loadBuses);
        this.generators = Objects.requireNonNull(generators);
        for (LfBus bus : buses) {
            activePowerLoss += bus.getGenerationTargetP() - bus.getLoadTargetP();
        }
        for (Map.Entry<LfBus, PowerShift> e : loadBuses.entrySet()) {
            activePowerLoss -= e.getValue().getActive();
        }
        for (LfGenerator generator : generators) {
            activePowerLoss += generator.getTargetP();
        }
    }

    public String getId() {
        return id;
    }

    public int getIndex() {
        return index;
    }

    public Set<LfBus> getBuses() {
        return buses;
    }

    public Set<LfBranch> getBranches() {
        return branches;
    }

    public double getActivePowerLoss() {
        return activePowerLoss;
    }

    public void apply(LoadFlowParameters parameters) {
        for (LfBranch branch : branches) {
            branch.setDisabled(true);
        }
        for (LfBus bus : buses) {
            bus.setDisabled(true);
        }
        for (Map.Entry<LfShunt, Double> e : shunts.entrySet()) {
            LfShunt shunt = e.getKey();
            shunt.setB(shunt.getB() - e.getValue());
        }
        for (Map.Entry<LfBus, PowerShift> e : loadBuses.entrySet()) {
            LfBus bus = e.getKey();
            PowerShift shift = e.getValue();
            bus.setLoadTargetP(bus.getLoadTargetP() - getUpdatedLoadP0(bus, parameters, shift.getActive(), shift.getVariableActive()));
            bus.setLoadTargetQ(bus.getLoadTargetQ() - shift.getReactive());
            bus.getLfLoads().setAbsVariableLoadTargetP(bus.getLfLoads().getAbsVariableLoadTargetP() - Math.abs(shift.getVariableActive()) * PerUnit.SB);
        }
        for (LfGenerator generator : generators) {
            generator.setTargetP(0);
            LfBus bus = generator.getBus();
            generator.setParticipating(false);
            if (generator.getGeneratorControlType() != LfGenerator.GeneratorControlType.OFF) {
                generator.setGeneratorControlType(LfGenerator.GeneratorControlType.OFF);
            } else {
                bus.setGenerationTargetQ(bus.getGenerationTargetQ() - generator.getTargetQ());
            }
        }
    }

    public static double getUpdatedLoadP0(LfBus bus, LoadFlowParameters parameters, double initialP0, double initialVariableActivePower) {
        double factor = 0.0;
        if (parameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD) {
            factor = Math.abs(initialP0) / (bus.getLfLoads().getAbsVariableLoadTargetP() / PerUnit.SB);
        } else if (parameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD) {
            factor = initialVariableActivePower / (bus.getLfLoads().getAbsVariableLoadTargetP() / PerUnit.SB);
        }
        return initialP0 + (bus.getLoadTargetP() - bus.getInitialLoadTargetP()) * factor;
    }

    public void writeJson(Writer writer) {
        Objects.requireNonNull(writer);
        try (JsonGenerator jsonGenerator = new JsonFactory()
                .createGenerator(writer)
                .useDefaultPrettyPrinter()) {
            jsonGenerator.writeStartObject();

            jsonGenerator.writeStringField("id", id);

            jsonGenerator.writeFieldName("buses");
            int[] sortedBuses = buses.stream().mapToInt(LfBus::getNum).sorted().toArray();
            jsonGenerator.writeArray(sortedBuses, 0, sortedBuses.length);

            jsonGenerator.writeFieldName("branches");
            int[] sortedBranches = branches.stream().mapToInt(LfBranch::getNum).sorted().toArray();
            jsonGenerator.writeArray(sortedBranches, 0, sortedBranches.length);

            jsonGenerator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
