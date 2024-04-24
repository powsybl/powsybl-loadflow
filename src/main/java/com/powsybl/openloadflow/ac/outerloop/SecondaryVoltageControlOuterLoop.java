/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.*;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class SecondaryVoltageControlOuterLoop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecondaryVoltageControlOuterLoop.class);

    public static final String NAME = "SecondaryVoltageControl";

    private static final double DV_EPS = 1E-4; // 0.1 kV
    private static final double DK_DIFF_MAX_EPS = 1E-3; // 1 MVar

    private final double minPlausibleTargetVoltage;

    private final double maxPlausibleTargetVoltage;

    @Override
    public String getName() {
        return NAME;
    }

    public SecondaryVoltageControlOuterLoop(double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage) {
        this.minPlausibleTargetVoltage = minPlausibleTargetVoltage;
        this.maxPlausibleTargetVoltage = maxPlausibleTargetVoltage;
    }

    private static Map<Integer, Integer> buildBusIndex(List<LfBus> buses) {
        Map<Integer, Integer> busIndex = new LinkedHashMap<>();
        for (int i = 0; i < buses.size(); i++) {
            var bus = buses.get(i);
            busIndex.put(bus.getNum(), i);
        }
        return busIndex;
    }

    static class SensitivityContext {

        private final Map<Integer, Integer> busNumToSensiColumn;

        private final DenseMatrix sensitivities;

        SensitivityContext(Map<Integer, Integer> busNumToSensiColumn, DenseMatrix sensitivities) {
            this.busNumToSensiColumn = Objects.requireNonNull(busNumToSensiColumn);
            this.sensitivities = Objects.requireNonNull(sensitivities);
        }

        static SensitivityContext create(List<LfBus> controlledBuses, AcLoadFlowContext context) {
            var busNumToSensiColumn = buildBusIndex(controlledBuses);

            DenseMatrix sensitivities = calculateSensitivityValues(controlledBuses, busNumToSensiColumn, context.getEquationSystem(),
                                                                   context.getJacobianMatrix());

            return new SensitivityContext(busNumToSensiColumn, sensitivities);
        }

        private static DenseMatrix calculateSensitivityValues(List<LfBus> controlledBuses, Map<Integer, Integer> busNumToSensiColumn,
                                                              EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                              JacobianMatrix<AcVariableType, AcEquationType> j) {
            DenseMatrix rhs = new DenseMatrix(equationSystem.getIndex().getSortedEquationsToSolve().size(), controlledBuses.size());
            for (LfBus controlledBus : controlledBuses) {
                equationSystem.getEquation(controlledBus.getNum(), AcEquationType.BUS_TARGET_V)
                        .ifPresent(equation -> rhs.set(equation.getColumn(), busNumToSensiColumn.get(controlledBus.getNum()), 1d));
            }
            j.solveTransposed(rhs);
            return rhs;
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcVariableType, AcEquationType> getCalculatedV(LfBus pilotBus) {
            return (EquationTerm<AcVariableType, AcEquationType>) pilotBus.getCalculatedV(); // this is safe
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcEquationType, AcEquationType> getQ1(LfBranch branch) {
            return (EquationTerm<AcEquationType, AcEquationType>) branch.getQ1();
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcEquationType, AcEquationType> getQ2(LfBranch branch) {
            return (EquationTerm<AcEquationType, AcEquationType>) branch.getQ2();
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcEquationType, AcEquationType> getQ(LfShunt shunt) {
            return (EquationTerm<AcEquationType, AcEquationType>) shunt.getQ();
        }

        double calculateSensiK(LfBus controllerBus, LfBus controlledBus) {
            return 2 * calculateSensiQ(controllerBus, controlledBus)
                    / (controllerBus.getMaxQ() - controllerBus.getMinQ());
        }

        /**
         * Calculate controlled bus voltage to controller bus reactive power injection sensitivity.
         */
        double calculateSensiQ(LfBus controllerBus, LfBus controlledBus) {
            int controlledBusSensiColumn = busNumToSensiColumn.get(controlledBus.getNum());

            MutableDouble sq = new MutableDouble();
            for (LfBranch branch : controllerBus.getBranches()) {
                // we can skip branches disconnected at the other side
                if (branch.getBus1() == controllerBus && branch.getBus2() != null) {
                    sq.add(getQ1(branch).calculateSensi(sensitivities, controlledBusSensiColumn));
                } else if (branch.getBus2() == controllerBus && branch.getBus1() != null) {
                    sq.add(getQ2(branch).calculateSensi(sensitivities, controlledBusSensiColumn));
                }
            }

            controllerBus.getShunt().ifPresent(shunt -> sq.add(getQ(shunt).calculateSensi(sensitivities, controlledBusSensiColumn)));
            controllerBus.getControllerShunt().ifPresent(shunt -> sq.add(getQ(shunt).calculateSensi(sensitivities, controlledBusSensiColumn)));
            controllerBus.getSvcShunt().ifPresent(shunt -> sq.add(getQ(shunt).calculateSensi(sensitivities, controlledBusSensiColumn)));

            return sq.getValue();
        }

        /**
         * Calculate controlled buses voltage to pilot bus voltage sensitivities.
         */
        double calculateSensiVpp(LfBus controlledBus, LfBus pilotBus) {
            int controlledBusSensiColumn = busNumToSensiColumn.get(controlledBus.getNum());
            return getCalculatedV(pilotBus).calculateSensi(sensitivities, controlledBusSensiColumn);
        }
    }

    private static double qToK(double q, LfBus controllerBus) {
        return (2 * q - controllerBus.getMaxQ() - controllerBus.getMinQ())
                / (controllerBus.getMaxQ() - controllerBus.getMinQ());
    }

    private static double calculateK(LfBus controllerBus) {
        double q = controllerBus.getQ().eval() + controllerBus.getLoadTargetQ();
        return qToK(q, controllerBus);
    }

    private static DenseMatrix createA(List<LfSecondaryVoltageControl> secondaryVoltageControls, Map<Integer, Integer> controllerBusIndex) {
        int n = controllerBusIndex.size();
        DenseMatrix a = new DenseMatrix(n, n);
        // build a block in the global matrix for each of the secondary voltage control
        for (LfSecondaryVoltageControl secondaryVoltageControl : secondaryVoltageControls) {
            List<LfBus> controllerBuses = secondaryVoltageControl.getControllerBuses();
            int nControl = controllerBuses.size();
            for (LfBus controllerBusI : controllerBuses) {
                for (LfBus controllerBusJ : controllerBuses) {
                    int i = controllerBusIndex.get(controllerBusI.getNum());
                    int j = controllerBusIndex.get(controllerBusJ.getNum());
                    a.set(i, j, i == j ? 1d - (1d / nControl) : -1d / nControl);
                }
            }
        }
        return a;
    }

    private static DenseMatrix createK0(List<LfBus> controllerBuses, Map<Integer, Integer> controllerBusIndex) {
        int n = controllerBuses.size();
        DenseMatrix k0 = new DenseMatrix(n, 1);
        for (LfBus controllerBus : controllerBuses) {
            int i = controllerBusIndex.get(controllerBus.getNum());
            k0.set(i, 0, calculateK(controllerBus));
        }
        return k0;
    }

    private static DenseMatrix createJk(SensitivityContext sensitivityContext, List<LfBus> controllerBuses, Map<Integer, Integer> controllerBusIndex) {
        int n = controllerBuses.size();
        DenseMatrix jK = new DenseMatrix(n, n);
        for (LfBus controllerBusI : controllerBuses) {
            for (LfBus controllerBusJ : controllerBuses) {
                int i = controllerBusIndex.get(controllerBusI.getNum());
                int j = controllerBusIndex.get(controllerBusJ.getNum());
                LfBus controlledBus2 = controllerBusJ.getGeneratorVoltageControl().orElseThrow().getControlledBus();
                jK.set(i, j, sensitivityContext.calculateSensiK(controllerBusI, controlledBus2));
            }
        }
        return jK;
    }

    private static DenseMatrix createJvpp(SensitivityContext sensitivityContext, LfBus pilotBus, List<LfBus> controllerBuses, Map<Integer, Integer> controllerBusIndex) {
        int n = controllerBuses.size();
        DenseMatrix jVpp = new DenseMatrix(n, 1);
        for (LfBus controllerBus : controllerBuses) {
            int i = controllerBusIndex.get(controllerBus.getNum());
            LfBus controlledBus = controllerBus.getGeneratorVoltageControl().orElseThrow().getControlledBus();
            jVpp.set(i, 0, sensitivityContext.calculateSensiVpp(controlledBus, pilotBus));
        }
        return jVpp;
    }

    private record KStats(double kAverage, double dkDiffMax, boolean allAtLimits) {
    }

    private static KStats calculateKStats(List<LfBus> controllerBuses) {
        double[] ks = controllerBuses.stream().mapToDouble(SecondaryVoltageControlOuterLoop::calculateK).toArray();
        double dkDiffMax = Arrays.stream(ks).max().orElseThrow() - Arrays.stream(ks).min().orElseThrow();
        double kAverage = Arrays.stream(ks).average().orElseThrow();
        boolean allAtLimits = Arrays.stream(ks).allMatch(k -> k > 1) || Arrays.stream(ks).allMatch(k -> k < -1);
        return new KStats(kAverage, dkDiffMax, allAtLimits);
    }

    private static double calculatePilotPointDv(LfSecondaryVoltageControl secondaryVoltageControl) {
        return secondaryVoltageControl.getTargetValue() - secondaryVoltageControl.getPilotBus().getV();
    }

    private Optional<List<String>> processSecondaryVoltageControl(List<LfSecondaryVoltageControl> secondaryVoltageControls,
                                                                  SensitivityContext sensitivityContext) {
        List<String> adjustedZoneNames = new ArrayList<>();

        for (LfSecondaryVoltageControl secondaryVoltageControl : secondaryVoltageControls) {
            double pilotDv = calculatePilotPointDv(secondaryVoltageControl);
            List<LfBus> controllerBuses = secondaryVoltageControl.getControllerBuses();
            KStats kStats = calculateKStats(controllerBuses);
            if (Math.abs(pilotDv) > DV_EPS || !kStats.allAtLimits() && Math.abs(kStats.dkDiffMax()) > DK_DIFF_MAX_EPS) {
                var pilotBus = secondaryVoltageControl.getPilotBus();
                if (LOGGER.isDebugEnabled()) {
                    int allControllerBusesCount = secondaryVoltageControl.getControllerBuses().size();
                    LOGGER.debug("Secondary voltage control of zone '{}': {}/{} controller buses available, pilot point dv is {} kV, controller buses dk diff max is {} (k average is {})",
                            secondaryVoltageControl.getZoneName(), controllerBuses.size(), allControllerBusesCount, pilotDv * pilotBus.getNominalV(), kStats.dkDiffMax(), kStats.kAverage());
                }
                adjustedZoneNames.add(secondaryVoltageControl.getZoneName());
            }
        }

        if (adjustedZoneNames.isEmpty()) {
            return Optional.of(Collections.emptyList());
        }

        List<LfBus> allControllerBuses = secondaryVoltageControls.stream()
                .flatMap(control -> control.getEnabledControllerBuses().stream())
                .toList();

        var controllerBusIndex = buildBusIndex(allControllerBuses);

        DenseMatrix a = createA(secondaryVoltageControls, controllerBusIndex);

        DenseMatrix k0 = createK0(allControllerBuses, controllerBusIndex);

        DenseMatrix rhs = a.times(k0, -1);

        DenseMatrix jK = createJk(sensitivityContext, allControllerBuses, controllerBusIndex);

        DenseMatrix b = a.times(jK);

        // replace last row for each of the secondary voltage control block
        for (LfSecondaryVoltageControl secondaryVoltageControl : secondaryVoltageControls) {
            List<LfBus> controllerBuses = secondaryVoltageControl.getControllerBuses();
            LfBus lastControllerBus = controllerBuses.get(controllerBuses.size() - 1);
            int i = controllerBusIndex.get(lastControllerBus.getNum());

            DenseMatrix jVpp = createJvpp(sensitivityContext, secondaryVoltageControl.getPilotBus(), allControllerBuses, controllerBusIndex);
            for (int j = 0; j < b.getColumnCount(); j++) {
                b.set(i, j, jVpp.get(j, 0));
            }

            double pilotDv = calculatePilotPointDv(secondaryVoltageControl);
            rhs.set(i, 0, pilotDv);
        }

        try (LUDecomposition luDecomposition = b.decomposeLU()) {
            luDecomposition.solve(rhs);
        }
        @SuppressWarnings("UnnecessaryLocalVariable")
        DenseMatrix dv = rhs;

        // compute new controlled bus target voltages
        Map<LfBus, Double> newControlledBusTargetV = new HashMap<>(allControllerBuses.size());
        for (LfBus controllerBus : allControllerBuses) {
            int i = controllerBusIndex.get(controllerBus.getNum());
            var vc = controllerBus.getGeneratorVoltageControl().orElseThrow();
            LfBus controlledBus = vc.getControlledBus();
            double newTargetV = vc.getTargetValue() + dv.get(i, 0);
            newControlledBusTargetV.put(controlledBus, newTargetV);
        }

        Map<LfBus, Double> notPlausibleNewControlledBusTargetV = newControlledBusTargetV.entrySet()
                .stream()
                .filter(e -> {
                    double newTargetV = e.getValue();
                    return LfGenerator.isTargetVoltageNotPlausible(newTargetV, minPlausibleTargetVoltage, maxPlausibleTargetVoltage);
                })
                .map(e -> {
                    // convert target to Kv for better display
                    LfBus controlledBus = e.getKey();
                    double newTargetV = e.getValue();
                    return Pair.of(controlledBus, newTargetV * controlledBus.getNominalV());
                })
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
        if (notPlausibleNewControlledBusTargetV.isEmpty()) {
            for (var e : newControlledBusTargetV.entrySet()) {
                LfBus controlledBus = e.getKey();
                double newTargetV = e.getValue();
                var vc = controlledBus.getGeneratorVoltageControl().orElseThrow();
                LOGGER.trace("Adjust target voltage of controlled bus '{}': {} -> {}",
                        controlledBus.getId(), vc.getTargetValue() * controlledBus.getNominalV(),
                        newTargetV * controlledBus.getNominalV());
                vc.setTargetValue(newTargetV);
            }
        } else {
            LOGGER.error("Skipping all controlled bus target voltage adjustment because some of the calculated new target voltages are not plausible: {}",
                    notPlausibleNewControlledBusTargetV);
            return Optional.empty();
        }

        return Optional.of(adjustedZoneNames);
    }

    private static void tryToReEnableHelpfulControllerBuses(LfNetwork network) {
        network.getEnabledSecondaryVoltageControls()
                .forEach(LfSecondaryVoltageControl::tryToReEnableHelpfulControllerBuses);
    }

    private static void logZonesWithAllBusControllersAtReactivePowerLimit(LfNetwork network) {
        List<String> zoneNames = network.getEnabledSecondaryVoltageControls().stream()
                .filter(control -> control.getEnabledControllerBuses().isEmpty())
                .map(LfSecondaryVoltageControl::getZoneName)
                .toList();
        if (!zoneNames.isEmpty()) {
            LOGGER.info("Controller buses of secondary voltage control zones {} cannot produce or absorb more reactive power",
                    zoneNames);
        }
    }

    @Override
    public OuterLoopStatus check(AcOuterLoopContext context, ReportNode reportNode) {
        LfNetwork network = context.getNetwork();

        // try to re-enable controller buses that have reached a reactive power limit (so bus switched to PQ) if they
        // can help to reach pilot point voltage target
        tryToReEnableHelpfulControllerBuses(network);

        // log zones where all controllers have reached reactive power limit
        logZonesWithAllBusControllersAtReactivePowerLimit(network);

        // for the remaining process, only keep secondary voltage control zones that have at least one enabled controller
        // bus, so zones that can still be adjusted
        List<LfSecondaryVoltageControl> secondaryVoltageControls = network.getEnabledSecondaryVoltageControls().stream()
                .filter(control -> control.findAnyControlledBusWithAtLeastOneControllerBusWithVoltageControlEnabled().isPresent())
                .toList();

        if (secondaryVoltageControls.isEmpty()) {
            return OuterLoopStatus.STABLE;
        }

        // compute target voltage sensitivities for all controlled buses
        List<LfBus> allControlledBuses = secondaryVoltageControls.stream()
                .flatMap(control -> control.getControlledBuses().stream())
                .toList();
        SensitivityContext sensitivityContext = SensitivityContext.create(allControlledBuses, context.getLoadFlowContext());

        OuterLoopStatus status = OuterLoopStatus.STABLE;

        List<String> adjustedZoneNames = processSecondaryVoltageControl(secondaryVoltageControls, sensitivityContext).orElse(null);
        if (adjustedZoneNames == null) {
            status = OuterLoopStatus.FAILED;
        } else {
            if (!adjustedZoneNames.isEmpty()) {
                status = OuterLoopStatus.UNSTABLE;
                LOGGER.info("{} secondary voltage control zones have been adjusted: {}",
                        adjustedZoneNames.size(), adjustedZoneNames);
            }
        }

        return status;
    }
}
