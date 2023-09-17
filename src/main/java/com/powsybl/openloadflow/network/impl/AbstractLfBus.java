/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.Evaluable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.util.EvaluableConstants.NAN;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractLfBus extends AbstractElement implements LfBus {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractLfBus.class);

    private static final double Q_DISPATCH_EPSILON = 1e-3;

    protected boolean slack = false;

    protected boolean reference = false;

    protected double v;

    protected Evaluable calculatedV = NAN;

    protected double angle;

    private boolean hasGeneratorsWithSlope;

    protected boolean generatorVoltageControlEnabled = false;

    protected Double generationTargetP;

    protected double generationTargetQ = 0;

    protected QLimitType qLimitType;

    protected final List<LfGenerator> generators = new ArrayList<>();

    protected LfShunt shunt;

    protected LfShunt controllerShunt;

    protected LfShunt svcShunt;

    protected boolean distributedOnConformLoad;

    protected final List<LfLoad> loads = new ArrayList<>();

    protected final List<LfBranch> branches = new ArrayList<>();

    protected final List<LfHvdc> hvdcs = new ArrayList<>();

    private GeneratorVoltageControl generatorVoltageControl;

    private ReactivePowerControl reactivePowerControl;

    protected TransformerVoltageControl transformerVoltageControl;

    protected ShuntVoltageControl shuntVoltageControl;

    protected Evaluable p = NAN;

    protected Evaluable q = NAN;

    protected double remoteVoltageControlReactivePercent = Double.NaN;

    protected final Map<LoadFlowModel, LfZeroImpedanceNetwork> zeroImpedanceNetwork = new EnumMap<>(LoadFlowModel.class);

    protected LfAsymBus asym;

    protected AbstractLfBus(LfNetwork network, double v, double angle, boolean distributedOnConformLoad) {
        super(network);
        this.v = v;
        this.angle = angle;
        this.distributedOnConformLoad = distributedOnConformLoad;
    }

    @Override
    public ElementType getType() {
        return ElementType.BUS;
    }

    @Override
    public boolean isSlack() {
        network.updateSlackBuses();
        return slack;
    }

    @Override
    public void setSlack(boolean slack) {
        this.slack = slack;
    }

    @Override
    public boolean isReference() {
        network.updateSlackBuses();
        return reference;
    }

    @Override
    public void setReference(boolean reference) {
        this.reference = reference;
    }

    @Override
    public double getTargetP() {
        return getGenerationTargetP() - getLoadTargetP();
    }

    @Override
    public double getTargetQ() {
        return getGenerationTargetQ() - getLoadTargetQ();
    }

    @Override
    public List<VoltageControl<?>> getVoltageControls() {
        List<VoltageControl<?>> voltageControls = new ArrayList<>(3);
        getGeneratorVoltageControl().ifPresent(voltageControls::add);
        getTransformerVoltageControl().ifPresent(voltageControls::add);
        getShuntVoltageControl().ifPresent(voltageControls::add);
        return voltageControls;
    }

    @Override
    public boolean isVoltageControlled() {
        return isGeneratorVoltageControlled() || isShuntVoltageControlled() || isTransformerVoltageControlled();
    }

    @Override
    public boolean isVoltageControlled(VoltageControl.Type type) {
        return switch (type) {
            case GENERATOR -> isGeneratorVoltageControlled();
            case TRANSFORMER -> isTransformerVoltageControlled();
            case SHUNT -> isShuntVoltageControlled();
        };
    }

    @Override
    public Optional<VoltageControl<?>> getVoltageControl(VoltageControl.Type type) {
        return getVoltageControls().stream().filter(vc -> vc.getType() == type).findAny();
    }

    @Override
    public Optional<VoltageControl<?>> getHighestPriorityMainVoltageControl() {
        return VoltageControl.findMainVoltageControlsSortedByPriority(this).stream().findFirst();
    }

    @Override
    public Optional<GeneratorVoltageControl> getGeneratorVoltageControl() {
        return Optional.ofNullable(generatorVoltageControl);
    }

    @Override
    public void setGeneratorVoltageControl(GeneratorVoltageControl generatorVoltageControl) {
        this.generatorVoltageControl = Objects.requireNonNull(generatorVoltageControl);
        if (hasGeneratorVoltageControllerCapability()) {
            this.generatorVoltageControlEnabled = true;
        } else if (!isGeneratorVoltageControlled()) {
            throw new PowsyblException("Setting inconsistent voltage control to bus " + getId());
        }
    }

    private boolean hasGeneratorVoltageControllerCapability() {
        return generatorVoltageControl != null && generatorVoltageControl.getControllerElements().contains(this);
    }

    @Override
    public Optional<ReactivePowerControl> getReactivePowerControl() {
        return Optional.ofNullable(reactivePowerControl);
    }

    @Override
    public void setReactivePowerControl(ReactivePowerControl reactivePowerControl) {
        this.reactivePowerControl = Objects.requireNonNull(reactivePowerControl);
    }

    @Override
    public boolean isGeneratorVoltageControlled() {
        return generatorVoltageControl != null && generatorVoltageControl.getControlledBus() == this;
    }

    @Override
    public List<LfGenerator> getGeneratorsControllingVoltageWithSlope() {
        return generators.stream().filter(gen -> gen.getGeneratorControlType() == LfGenerator.GeneratorControlType.VOLTAGE && gen.getSlope() != 0).collect(Collectors.toList());
    }

    @Override
    public boolean hasGeneratorsWithSlope() {
        return hasGeneratorsWithSlope;
    }

    @Override
    public void removeGeneratorSlopes() {
        hasGeneratorsWithSlope = false;
        generators.forEach(g -> g.setSlope(0));
    }

    @Override
    public boolean isGeneratorVoltageControlEnabled() {
        return generatorVoltageControlEnabled;
    }

    @Override
    public void setGeneratorVoltageControlEnabled(boolean generatorVoltageControlEnabled) {
        if (this.generatorVoltageControlEnabled != generatorVoltageControlEnabled) {
            this.generatorVoltageControlEnabled = generatorVoltageControlEnabled;
            for (LfNetworkListener listener : network.getListeners()) {
                listener.onGeneratorVoltageControlChange(this, generatorVoltageControlEnabled);
            }
        }
    }

    private static LfLoadModel createLfLoadModel(LoadModel loadModel) {
        if (loadModel == null) {
            return null;
        }
        if (loadModel.getType() == LoadModelType.ZIP) {
            ZipLoadModel zipLoadModel = (ZipLoadModel) loadModel;
            return new LfLoadModel(List.of(new LfLoadModel.Term(0, zipLoadModel.getC0p()),
                                           new LfLoadModel.Term(1, zipLoadModel.getC1p()),
                                           new LfLoadModel.Term(2, zipLoadModel.getC2p())),
                                   List.of(new LfLoadModel.Term(0, zipLoadModel.getC0q()),
                                           new LfLoadModel.Term(1, zipLoadModel.getC1q()),
                                           new LfLoadModel.Term(2, zipLoadModel.getC2q())));
        } else if (loadModel.getType() == LoadModelType.EXPONENTIAL) {
            ExponentialLoadModel expoLoadModel = (ExponentialLoadModel) loadModel;
            return new LfLoadModel(List.of(new LfLoadModel.Term(expoLoadModel.getNp(), 1)),
                                   List.of(new LfLoadModel.Term(expoLoadModel.getNq(), 1)));
        } else {
            throw new PowsyblException("Unsupported load model: " + loadModel.getType());
        }
    }

    protected LfLoadImpl getOrCreateLfLoad(LoadModel loadModel) {
        LfLoadModel lfLoadModel = createLfLoadModel(loadModel);
        return (LfLoadImpl) loads.stream().filter(l -> Objects.equals(l.getLoadModel().orElse(null), lfLoadModel)).findFirst()
                .orElseGet(() -> {
                    LfLoadImpl l = new LfLoadImpl(AbstractLfBus.this, distributedOnConformLoad, lfLoadModel);
                    loads.add(l);
                    return l;
                });
    }

    void addLoad(Load load, LfNetworkParameters parameters) {
        getOrCreateLfLoad(load.getModel().orElse(null)).add(load, parameters);
    }

    void addLccConverterStation(LccConverterStation lccCs, LfNetworkParameters parameters) {
        getOrCreateLfLoad(null).add(lccCs, parameters);
    }

    protected void add(LfGenerator generator) {
        generators.add(generator);
        generator.setBus(this);
        if (generator.getGeneratorControlType() != LfGenerator.GeneratorControlType.VOLTAGE && !Double.isNaN(generator.getTargetQ())) {
            generationTargetQ += generator.getTargetQ();
        }
    }

    void addGenerator(Generator generator, LfNetworkParameters parameters, LfNetworkLoadingReport report) {
        add(LfGeneratorImpl.create(generator, network, parameters, report));
    }

    void addStaticVarCompensator(StaticVarCompensator staticVarCompensator, LfNetworkParameters parameters,
                                 LfNetworkLoadingReport report) {
        if (staticVarCompensator.getRegulationMode() != StaticVarCompensator.RegulationMode.OFF) {
            LfStaticVarCompensatorImpl lfSvc = LfStaticVarCompensatorImpl.create(staticVarCompensator, network, this, parameters, report);
            add(lfSvc);
            if (lfSvc.getSlope() != 0) {
                hasGeneratorsWithSlope = true;
            }
            if (lfSvc.getB0() != 0) {
                svcShunt = LfStandbyAutomatonShunt.create(lfSvc);
                lfSvc.setStandByAutomatonShunt(svcShunt);
            }
        }
    }

    void addVscConverterStation(VscConverterStation vscCs, LfNetworkParameters parameters, LfNetworkLoadingReport report) {
        add(LfVscConverterStationImpl.create(vscCs, network, parameters, report));
    }

    void addBattery(Battery generator, LfNetworkParameters parameters, LfNetworkLoadingReport report) {
        add(LfBatteryImpl.create(generator, network, parameters, report));
    }

    void setShuntCompensators(List<ShuntCompensator> shuntCompensators, LfNetworkParameters parameters) {
        if (!parameters.isShuntVoltageControl() && !shuntCompensators.isEmpty()) {
            shunt = new LfShuntImpl(shuntCompensators, network, this, false, parameters);
        } else {
            List<ShuntCompensator> controllerShuntCompensators = shuntCompensators.stream()
                    .filter(ShuntCompensator::isVoltageRegulatorOn)
                    .collect(Collectors.toList());
            if (!controllerShuntCompensators.isEmpty()) {
                controllerShunt = new LfShuntImpl(controllerShuntCompensators, network, this, true, parameters);
            }
            List<ShuntCompensator> fixedShuntCompensators = shuntCompensators.stream()
                    .filter(sc -> !sc.isVoltageRegulatorOn())
                    .collect(Collectors.toList());
            if (!fixedShuntCompensators.isEmpty()) {
                shunt = new LfShuntImpl(fixedShuntCompensators, network, this, false, parameters);
            }
        }
    }

    @Override
    public void invalidateGenerationTargetP() {
        generationTargetP = null;
    }

    @Override
    public double getGenerationTargetP() {
        if (generationTargetP == null) {
            generationTargetP = generators.stream().mapToDouble(LfGenerator::getTargetP).sum();
        }
        return generationTargetP;
    }

    @Override
    public double getGenerationTargetQ() {
        return generationTargetQ;
    }

    @Override
    public void setGenerationTargetQ(double generationTargetQ) {
        if (generationTargetQ != this.generationTargetQ) {
            double oldGenerationTargetQ = this.generationTargetQ;
            this.generationTargetQ = generationTargetQ;
            for (LfNetworkListener listener : network.getListeners()) {
                listener.onGenerationReactivePowerTargetChange(this, oldGenerationTargetQ, generationTargetQ);
            }
        }
    }

    @Override
    public double getLoadTargetP() {
        return loads.stream().mapToDouble(LfLoad::getTargetP).sum();
    }

    @Override
    public double getLoadTargetQ() {
        return loads.stream().mapToDouble(LfLoad::getTargetQ).sum();
    }

    private double getLimitQ(ToDoubleFunction<LfGenerator> limitQ) {
        return generators.stream()
                .mapToDouble(generator -> generator.getGeneratorControlType() == LfGenerator.GeneratorControlType.VOLTAGE ?
                        limitQ.applyAsDouble(generator) : generator.getTargetQ()).sum();
    }

    @Override
    public double getMinQ() {
        return getLimitQ(LfGenerator::getMinQ);
    }

    @Override
    public double getMaxQ() {
        return getLimitQ(LfGenerator::getMaxQ);
    }

    @Override
    public Optional<QLimitType> getQLimitType() {
        return Optional.ofNullable(this.qLimitType);
    }

    @Override
    public void setQLimitType(QLimitType qLimitType) {
        this.qLimitType = qLimitType;
    }

    @Override
    public double getV() {
        return v / getNominalV();
    }

    @Override
    public void setV(double v) {
        this.v = v * getNominalV();
    }

    @Override
    public Evaluable getCalculatedV() {
        return calculatedV;
    }

    @Override
    public void setCalculatedV(Evaluable calculatedV) {
        this.calculatedV = Objects.requireNonNull(calculatedV);
    }

    @Override
    public double getAngle() {
        return angle;
    }

    @Override
    public void setAngle(double angle) {
        this.angle = angle;
    }

    @Override
    public Optional<LfShunt> getShunt() {
        return Optional.ofNullable(shunt);
    }

    @Override
    public Optional<LfShunt> getControllerShunt() {
        return Optional.ofNullable(controllerShunt);
    }

    @Override
    public Optional<LfShunt> getSvcShunt() {
        return Optional.ofNullable(svcShunt);
    }

    @Override
    public List<LfGenerator> getGenerators() {
        return generators;
    }

    @Override
    public List<LfLoad> getLoads() {
        return loads;
    }

    @Override
    public List<LfBranch> getBranches() {
        return branches;
    }

    @Override
    public void addBranch(LfBranch branch) {
        branches.add(Objects.requireNonNull(branch));
    }

    @Override
    public void addHvdc(LfHvdc hvdc) {
        hvdcs.add(Objects.requireNonNull(hvdc));
    }

    protected static double dispatchQ(List<LfGenerator> generatorsThatControlVoltage, boolean reactiveLimits, double qToDispatch) {
        double residueQ = 0;
        if (generatorsThatControlVoltage.isEmpty()) {
            throw new IllegalArgumentException("the generator list to dispatch Q can not be empty");
        }
        double qToBeDispatchedByGenerator = qToDispatch / generatorsThatControlVoltage.size();
        Iterator<LfGenerator> itG = generatorsThatControlVoltage.iterator();
        while (itG.hasNext()) {
            LfGenerator generator = itG.next();
            double generatorAlreadyCalculatedQ = generator.getCalculatedQ();
            if (reactiveLimits && qToBeDispatchedByGenerator + generatorAlreadyCalculatedQ < generator.getMinQ()) {
                residueQ += qToBeDispatchedByGenerator + generatorAlreadyCalculatedQ - generator.getMinQ();
                generator.setCalculatedQ(generator.getMinQ());
                itG.remove();
            } else if (reactiveLimits && qToBeDispatchedByGenerator + generatorAlreadyCalculatedQ > generator.getMaxQ()) {
                residueQ += qToBeDispatchedByGenerator + generatorAlreadyCalculatedQ - generator.getMaxQ();
                generator.setCalculatedQ(generator.getMaxQ());
                itG.remove();
            } else {
                generator.setCalculatedQ(generatorAlreadyCalculatedQ + qToBeDispatchedByGenerator);
            }
        }
        return residueQ;
    }

    void updateGeneratorsState(double generationQ, boolean reactiveLimits) {
        double qToDispatch = generationQ;
        List<LfGenerator> generatorsThatControlVoltage = new LinkedList<>();
        for (LfGenerator generator : generators) {
            if (generator.getGeneratorControlType() == LfGenerator.GeneratorControlType.VOLTAGE) {
                generatorsThatControlVoltage.add(generator);
            } else {
                qToDispatch -= generator.getTargetQ();
            }
        }
        List<LfGenerator> initialGeneratorsThatControlVoltage = new LinkedList<>(generatorsThatControlVoltage);
        for (LfGenerator generator : generatorsThatControlVoltage) {
            generator.setCalculatedQ(0);
        }
        while (!generatorsThatControlVoltage.isEmpty() && Math.abs(qToDispatch) > Q_DISPATCH_EPSILON) {
            qToDispatch = dispatchQ(generatorsThatControlVoltage, reactiveLimits, qToDispatch);
        }
        if (!initialGeneratorsThatControlVoltage.isEmpty() && Math.abs(qToDispatch) > Q_DISPATCH_EPSILON) {
            // FIXME
            // We have to much reactive power to dispatch, which is linked to a bus that has been forced to remain PV to
            // ease the convergence. Updating a generator reactive power outside its reactive limits is a quick fix.
            // It could be better to return a global failed status.
            dispatchQ(initialGeneratorsThatControlVoltage, false, qToDispatch);
        }
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters) {
        // update generator reactive power
        updateGeneratorsState(generatorVoltageControlEnabled ? (q.eval() + getLoadTargetQ()) : generationTargetQ, parameters.isReactiveLimits());

        // update load power
        for (LfLoad load : loads) {
            load.updateState(parameters.isLoadPowerFactorConstant(),
                    parameters.isBreakers());
        }
    }

    @Override
    public Optional<TransformerVoltageControl> getTransformerVoltageControl() {
        return Optional.ofNullable(transformerVoltageControl);
    }

    @Override
    public boolean isTransformerVoltageControlled() {
        return transformerVoltageControl != null && transformerVoltageControl.getControlledBus() == this;
    }

    @Override
    public void setTransformerVoltageControl(TransformerVoltageControl transformerVoltageControl) {
        this.transformerVoltageControl = transformerVoltageControl;
    }

    @Override
    public Optional<ShuntVoltageControl> getShuntVoltageControl() {
        return Optional.ofNullable(shuntVoltageControl);
    }

    @Override
    public boolean isShuntVoltageControlled() {
        return shuntVoltageControl != null && shuntVoltageControl.getControlledBus() == this;
    }

    @Override
    public void setShuntVoltageControl(ShuntVoltageControl shuntVoltageControl) {
        this.shuntVoltageControl = shuntVoltageControl;
    }

    @Override
    public void setDisabled(boolean disabled) {
        super.setDisabled(disabled);
        if (shunt != null) {
            shunt.setDisabled(disabled);
        }
        if (controllerShunt != null) {
            controllerShunt.setDisabled(disabled);
        }
    }

    @Override
    public void setP(Evaluable p) {
        this.p = Objects.requireNonNull(p);
    }

    @Override
    public Evaluable getP() {
        return p;
    }

    @Override
    public void setQ(Evaluable q) {
        this.q = Objects.requireNonNull(q);
    }

    @Override
    public Evaluable getQ() {
        return q;
    }

    @Override
    public Map<LfBus, List<LfBranch>> findNeighbors() {
        Map<LfBus, List<LfBranch>> neighbors = new LinkedHashMap<>(branches.size());
        for (LfBranch branch : branches) {
            if (branch.isConnectedAtBothSides()) {
                LfBus otherBus = branch.getBus1() == this ? branch.getBus2() : branch.getBus1();
                neighbors.computeIfAbsent(otherBus, k -> new ArrayList<>())
                        .add(branch);
            }
        }
        return neighbors;
    }

    @Override
    public double getRemoteVoltageControlReactivePercent() {
        return remoteVoltageControlReactivePercent;
    }

    @Override
    public void setRemoteVoltageControlReactivePercent(double remoteVoltageControlReactivePercent) {
        this.remoteVoltageControlReactivePercent = remoteVoltageControlReactivePercent;
    }

    @Override
    public double getMismatchP() {
        return p.eval() - getTargetP(); // slack bus can also have real injection connected
    }

    @Override
    public void setZeroImpedanceNetwork(LoadFlowModel loadFlowModel, LfZeroImpedanceNetwork zeroImpedanceNetwork) {
        Objects.requireNonNull(zeroImpedanceNetwork);
        this.zeroImpedanceNetwork.put(loadFlowModel, zeroImpedanceNetwork);
    }

    @Override
    public LfZeroImpedanceNetwork getZeroImpedanceNetwork(LoadFlowModel loadFlowModel) {
        return zeroImpedanceNetwork.get(loadFlowModel);
    }

    @Override
    public LfAsymBus getAsym() {
        return asym;
    }

    @Override
    public void setAsym(LfAsymBus asym) {
        this.asym = asym;
        asym.setBus(this);
    }
}
