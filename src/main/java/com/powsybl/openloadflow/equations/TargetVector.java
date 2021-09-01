/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.openloadflow.network.AbstractLfNetworkListener;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfNetwork;

import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class TargetVector<V extends Enum<V> & VariableType, E extends Enum<E> & VariableType> extends AbstractLfNetworkListener implements EquationSystemListener<V, E> {

    private final LfNetwork network;

    private final EquationSystem<V, E> equationSystem;

    private double[] array;

    private enum Status {
        VALID,
        VECTOR_INVALID, // structure has changed
        VALUES_INVALID // same structure but values have to be updated
    }

    private Status status = Status.VECTOR_INVALID;

    public TargetVector(LfNetwork network, EquationSystem<V, E> equationSystem) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        network.addListener(this);
        equationSystem.addListener(this);
    }

    private void invalidateValues() {
        if (status == Status.VALID) {
            status = Status.VALUES_INVALID;
        }
    }

    @Override
    public void onLoadActivePowerTargetChange(LfBus bus, double oldLoadTargetP, double newLoadTargetP) {
        invalidateValues();
    }

    @Override
    public void onLoadReactivePowerTargetChange(LfBus bus, double oldLoadTargetQ, double newLoadTargetQ) {
        invalidateValues();
    }

    @Override
    public void onGenerationActivePowerTargetChange(LfGenerator generator, double oldGenerationTargetP, double newGenerationTargetP) {
        invalidateValues();
    }

    @Override
    public void onGenerationReactivePowerTargetChange(LfBus bus, double oldGenerationTargetQ, double newGenerationTargetQ) {
        invalidateValues();
    }

    @Override
    public void onEquationChange(Equation<V, E> equation, EquationEventType eventType) {
        status = Status.VECTOR_INVALID;
    }

    @Override
    public void onEquationTermChange(EquationTerm<V, E> term, EquationTermEventType eventType) {
        // nothing to do
    }

    @Override
    public void onStateUpdate(double[] x) {
        // nothing to do
    }

    public double[] toArray() {
        switch (status) {
            case VECTOR_INVALID:
                createArray();
                break;

            case VALUES_INVALID:
                updateArray();
                break;

            default:
                // nothing to do
                break;
        }
        return array;
    }

    public static <V extends Enum<V> & VariableType, E extends Enum<E> & VariableType> double[] createArray(LfNetwork network, EquationSystem<V, E> equationSystem) {
        NavigableMap<Equation<V, E>, NavigableMap<Variable<V>, List<EquationTerm<V, E>>>> sortedEquationsToSolve = equationSystem.getSortedEquationsToSolve();
        double[] array = new double[sortedEquationsToSolve.size()];
        for (Equation<V, E> equation : sortedEquationsToSolve.keySet()) {
            equation.initTarget(network, array);
        }
        return array;
    }

    private void createArray() {
        array = createArray(network, equationSystem);
        status = Status.VALID;
    }

    private void updateArray() {
        NavigableMap<Equation<V, E>, NavigableMap<Variable<V>, List<EquationTerm<V, E>>>> sortedEquationsToSolve = equationSystem.getSortedEquationsToSolve();
        for (Equation<V, E> equation : sortedEquationsToSolve.keySet()) {
            equation.initTarget(network, array);
        }
        status = Status.VALID;
    }
}
