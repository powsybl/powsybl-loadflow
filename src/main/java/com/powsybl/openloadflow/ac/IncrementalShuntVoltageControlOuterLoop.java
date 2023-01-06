/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.reporter.Reporter;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.outerloop.OuterLoop;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopContext;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfShuntImpl;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Hadrien Godard <hadrien.godard at artelys.com>
 */

public class IncrementalShuntVoltageControlOuterLoop implements OuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalShuntVoltageControlOuterLoop.class);

    private static final int MAX_DIRECTION_CHANGE = 2;

    private static final class ControllerContext {

        private final MutableInt directionChangeCount = new MutableInt();

        private LfShuntImpl.Controller.AllowedDirection allowedDirection = LfShuntImpl.Controller.AllowedDirection.BOTH;

        public MutableInt getDirectionChangeCount() {
            return directionChangeCount;
        }

        private LfShuntImpl.Controller.AllowedDirection getAllowedDirection() {
            return allowedDirection;
        }

        private void setAllowedDirection(LfShuntImpl.Controller.AllowedDirection allowedDirection) {
            this.allowedDirection = Objects.requireNonNull(allowedDirection);
        }
    }

    private static final class ContextData {

        private final Map<String, ControllerContext> controllersContexts = new HashMap<>();

        private Map<String, ControllerContext> getControllersContexts() {
            return controllersContexts;
        }
    }

    private static List<LfShunt> getControllerShunts(LfNetwork network) {
        return network.getBuses().stream()
                .flatMap(bus -> bus.getControllerShunt().stream())
                .filter(controllerShunt -> !controllerShunt.isDisabled() && controllerShunt.hasVoltageControlCapability())
                .collect(Collectors.toList());
    }

    protected static boolean checkTargetDeadband(Double targetDeadband, double difference) {
        return (targetDeadband != null && Math.abs(difference) > targetDeadband / 2) || targetDeadband == null;
    }

    @Override
    public String getType() {
        return "Incremental Shunt voltage control";
    }

    @Override
    public void initialize(OuterLoopContext context) {
        var contextData = new ContextData();
        context.setData(contextData);

        // All shunt voltage control are disabled for the first equation system resolution.
        for (LfShunt shunt : getControllerShunts(context.getNetwork())) {
            shunt.getVoltageControl().ifPresent(voltageControl -> shunt.setVoltageControlEnabled(false));
            // FIX ME: not safe casting
            for (LfShuntImpl.Controller lfShuntController : ((LfShuntImpl) shunt).getControllers()) {
                contextData.getControllersContexts().put(lfShuntController.getId(), new ControllerContext());
            }
        }
    }

    private static void updateAllowedDirection(ControllerContext controllerContext, LfShuntImpl.Controller.Direction direction) {
        if (controllerContext.getDirectionChangeCount().getValue() <= MAX_DIRECTION_CHANGE) {
            if (!controllerContext.getAllowedDirection().equals(direction.getAllowedDirection())) {
                // both vs increase or decrease
                // increase vs decrease or decrease vs increase
                controllerContext.getDirectionChangeCount().increment();
            }
            controllerContext.setAllowedDirection(direction.getAllowedDirection());
        }
    }

    private void adjustB(List<LfShunt> controllerShunts, LfBus controlledBus, ContextData contextData, int[] controllerShuntIndex,
                                              DenseMatrix sensitivities, double diffV, MutableObject<OuterLoopStatus> status) {
        // several shunts control the same bus
        double remainingDiffV = diffV;
        boolean hasChanged = true;
        while (hasChanged) {
            hasChanged = false;
            for (LfShunt controllerShunt : controllerShunts) {
                double sensitivity = ((EquationTerm<AcVariableType, AcEquationType>) controlledBus.getCalculatedV())
                        .calculateSensi(sensitivities, controllerShuntIndex[controllerShunt.getNum()]);
                double targetDeadband = controllerShunt.getShuntVoltageControlTargetDeadband().orElse(null);
                // FIX ME: Not safe casting
                for (LfShuntImpl.Controller controller : ((LfShuntImpl) controllerShunt).getControllers()) {
                    var controllerContext = contextData.getControllersContexts().get(controllerShunt.getId());
                    if (checkTargetDeadband(targetDeadband, remainingDiffV)) {
                        double previousB = controller.getB();
                        double deltaB = remainingDiffV / sensitivity;
                        LfShuntImpl.Controller.Direction direction = controller.updateTapPositionB(deltaB, 1, controllerContext.getAllowedDirection()).orElse(null);
                        if (direction != null) {
                            updateAllowedDirection(controllerContext, direction);
                            remainingDiffV -= (controller.getB() - previousB) * sensitivity;
                            hasChanged = true;
                            status.setValue(OuterLoopStatus.UNSTABLE);
                            LOGGER.debug("Increment shunt susceptance of '{}': {} -> {}", controller.getId(), previousB, controller.getB());
                        }
                    } else {
                        LOGGER.trace("Controller shunt '{}' is in its deadband: deadband {} vs voltage difference {}", controllerShunt.getId(), targetDeadband, Math.abs(diffV));
                    }
                }
            }
        }
    }

    @Override
    public OuterLoopStatus check(OuterLoopContext context, Reporter reporter) {
        MutableObject<OuterLoopStatus> status = new MutableObject<>(OuterLoopStatus.STABLE);

        LfNetwork network = context.getNetwork();
        List<LfShunt> controllerShunts = getControllerShunts(network);
        int[] controllerShuntIndex = new int[network.getShunts().size()];
        for (int i = 0; i < controllerShunts.size(); i++) {
            LfShunt controllerShunt = controllerShunts.get(i);
            controllerShuntIndex[controllerShunt.getNum()] = i;
        }

        DenseMatrix sensitivities = calculateSensitivityValues(controllerShunts, controllerShuntIndex,
                context.getAcLoadFlowContext().getEquationSystem(),
                context.getAcLoadFlowContext().getJacobianMatrix());

        var contextData = (IncrementalShuntVoltageControlOuterLoop.ContextData) context.getData();

        network.getBuses().stream()
                .filter(LfBus::isShuntVoltageControlled)
                .forEach(controlledBus -> {
                    ShuntVoltageControl voltageControl = controlledBus.getShuntVoltageControl().orElseThrow();
                    double targetV = voltageControl.getTargetValue();
                    double v = voltageControl.getControlled().getV();
                    double diffV = targetV - v;
                    List<LfShunt> controllers = voltageControl.getControllers();
                    adjustB(controllers, controlledBus, contextData, controllerShuntIndex, sensitivities, diffV, status);
                });
        return status.getValue();
    }

    private static DenseMatrix calculateSensitivityValues(List<LfShunt> controllerShunts, int[] controllerShuntIndex,
                                                          EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                          JacobianMatrix<AcVariableType, AcEquationType> j) {
        DenseMatrix rhs = new DenseMatrix(equationSystem.getIndex().getSortedEquationsToSolve().size(), controllerShunts.size());
        for (LfShunt controllerShunt : controllerShunts) {
            Variable<AcVariableType> b = equationSystem.getVariable(controllerShunt.getNum(), AcVariableType.SHUNT_B);
            equationSystem.getEquation(controllerShunt.getNum(), AcEquationType.SHUNT_TARGET_B).ifPresent(equation -> {
                var term = equation.getTerms().get(0);
                rhs.set(equation.getColumn(), controllerShuntIndex[controllerShunt.getNum()], term.der(b));
            });
        }
        j.solveTransposed(rhs);
        return rhs;
    }
}
