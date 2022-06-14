/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.extensions.AbstractExtension;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.ac.VoltageMagnitudeInitializer;
import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationParameters;
import com.powsybl.openloadflow.ac.nr.DefaultNewtonRaphsonStoppingCriteria;
import com.powsybl.openloadflow.ac.nr.NewtonRaphsonParameters;
import com.powsybl.openloadflow.ac.outerloop.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.outerloop.OuterLoop;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcValueVoltageInitializer;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class OpenLoadFlowParameters extends AbstractExtension<LoadFlowParameters> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenLoadFlowParameters.class);

    public static final SlackBusSelectionMode SLACK_BUS_SELECTION_DEFAULT_VALUE = SlackBusSelectionMode.MOST_MESHED;

    public static final LowImpedanceBranchMode LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE = LowImpedanceBranchMode.REPLACE_BY_ZERO_IMPEDANCE_LINE;

    public static final boolean THROWS_EXCEPTION_IN_CASE_OF_SLACK_DISTRIBUTION_FAILURE_DEFAULT_VALUE = false;

    public static final boolean VOLTAGE_REMOTE_CONTROL_DEFAULT_VALUE = true;

    public static final boolean REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE = false;

    public static final boolean LOAD_POWER_FACTOR_CONSTANT_DEFAULT_VALUE = false;

    public static final boolean ADD_RATIO_TO_LINES_WITH_DIFFERENT_NOMINAL_VOLTAGE_AT_BOTH_ENDS_DEFAULT_VALUE = true;

    /**
     * Slack bus maximum active power mismatch in MW: 1 Mw => 10^-2 in p.u
     */
    public static final double SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE = 1.0;

    public static final boolean VOLTAGE_PER_REACTIVE_POWER_CONTROL_DEFAULT_VALUE = false;

    public static final double DC_POWER_FACTOR_DEFAULT_VALUE = 1.0;

    public static final String SLACK_BUS_SELECTION_PARAM_NAME = "slackBusSelectionMode";

    public static final String SLACK_BUSES_IDS_PARAM_NAME = "slackBusesIds";

    public static final String THROWS_EXCEPTION_IN_CASE_OF_SLACK_DISTRIBUTION_FAILURE_PARAM_NAME = "throwsExceptionInCaseOfSlackDistributionFailure";

    public static final String VOLTAGE_REMOTE_CONTROL_PARAM_NAME = "voltageRemoteControl";

    public static final String REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME = "reactivePowerRemoteControl";

    public static final String LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME = "lowImpedanceBranchMode";

    public static final String LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME = "loadPowerFactorConstant";

    public static final String PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME = "plausibleActivePowerLimit";

    public static final String ADD_RATIO_TO_LINES_WITH_DIFFERENT_NOMINAL_VOLTAGE_AT_BOTH_ENDS_NAME = "addRatioToLinesWithDifferentNominalVoltageAtBothEnds";

    public static final String SLACK_BUS_P_MAX_MISMATCH_NAME = "slackBusPMaxMismatch";

    public static final String VOLTAGE_PER_REACTIVE_POWER_CONTROL_NAME = "voltagePerReactivePowerControl";

    public static final String MAX_ITERATION_NAME = "maxIteration";

    public static final String NEWTON_RAPHSON_CONV_EPS_PER_EQ_NAME = "newtonRaphsonConvEpsPerEq";

    public static final String VOLTAGE_INIT_MODE_OVERRIDE_NAME = "voltageInitModeOverride";

    public static final String TRANSFORMER_VOLTAGE_CONTROL_MODE_NAME = "transformerVoltageControlMode";

    public static final String DC_POWER_FACTOR_NAME = "dcPowerFactor";

    public static final List<String> SPECIFIC_PARAMETERS_NAMES = List.of(SLACK_BUS_SELECTION_PARAM_NAME,
                                                                         SLACK_BUSES_IDS_PARAM_NAME,
                                                                         LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME,
                                                                         VOLTAGE_REMOTE_CONTROL_PARAM_NAME,
                                                                         THROWS_EXCEPTION_IN_CASE_OF_SLACK_DISTRIBUTION_FAILURE_PARAM_NAME,
                                                                         LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME,
                                                                         PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME,
                                                                         ADD_RATIO_TO_LINES_WITH_DIFFERENT_NOMINAL_VOLTAGE_AT_BOTH_ENDS_NAME,
                                                                         SLACK_BUS_P_MAX_MISMATCH_NAME,
                                                                         VOLTAGE_PER_REACTIVE_POWER_CONTROL_NAME,
                                                                         REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME,
                                                                         MAX_ITERATION_NAME,
                                                                         NEWTON_RAPHSON_CONV_EPS_PER_EQ_NAME,
                                                                         VOLTAGE_INIT_MODE_OVERRIDE_NAME,
                                                                         TRANSFORMER_VOLTAGE_CONTROL_MODE_NAME,
                                                                         DC_POWER_FACTOR_NAME);

    public enum VoltageInitModeOverride {
        NONE,
        VOLTAGE_MAGNITUDE,
        FULL_VOLTAGE
    }

    public static final VoltageInitModeOverride VOLTAGE_INIT_MODE_OVERRIDE_DEFAULT_VALUE = VoltageInitModeOverride.NONE;

    public enum TransformerVoltageControlMode {
        WITH_GENERATOR_VOLTAGE_CONTROL,
        AFTER_GENERATOR_VOLTAGE_CONTROL
    }

    public static final TransformerVoltageControlMode TRANSFORMER_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE = TransformerVoltageControlMode.WITH_GENERATOR_VOLTAGE_CONTROL;

    private SlackBusSelectionMode slackBusSelectionMode = SLACK_BUS_SELECTION_DEFAULT_VALUE;

    private List<String> slackBusesIds = Collections.emptyList();

    private boolean throwsExceptionInCaseOfSlackDistributionFailure = THROWS_EXCEPTION_IN_CASE_OF_SLACK_DISTRIBUTION_FAILURE_DEFAULT_VALUE;

    private boolean voltageRemoteControl = VOLTAGE_REMOTE_CONTROL_DEFAULT_VALUE;

    private LowImpedanceBranchMode lowImpedanceBranchMode = LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE;

    public enum LowImpedanceBranchMode {
        REPLACE_BY_ZERO_IMPEDANCE_LINE,
        REPLACE_BY_MIN_IMPEDANCE_LINE
    }

    private boolean loadPowerFactorConstant = LOAD_POWER_FACTOR_CONSTANT_DEFAULT_VALUE;

    private double plausibleActivePowerLimit = LfNetworkParameters.PLAUSIBLE_ACTIVE_POWER_LIMIT_DEFAULT_VALUE;

    private boolean addRatioToLinesWithDifferentNominalVoltageAtBothEnds = ADD_RATIO_TO_LINES_WITH_DIFFERENT_NOMINAL_VOLTAGE_AT_BOTH_ENDS_DEFAULT_VALUE;

    private double slackBusPMaxMismatch = SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE;

    private boolean voltagePerReactivePowerControl = VOLTAGE_PER_REACTIVE_POWER_CONTROL_DEFAULT_VALUE;

    private boolean reactivePowerRemoteControl = REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE;

    private int maxIteration = NewtonRaphsonParameters.DEFAULT_MAX_ITERATION;

    private double newtonRaphsonConvEpsPerEq = DefaultNewtonRaphsonStoppingCriteria.DEFAULT_CONV_EPS_PER_EQ;

    private VoltageInitModeOverride voltageInitModeOverride = VOLTAGE_INIT_MODE_OVERRIDE_DEFAULT_VALUE;

    private TransformerVoltageControlMode transformerVoltageControlMode = TRANSFORMER_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE;

    private double dcPowerFactor = DC_POWER_FACTOR_DEFAULT_VALUE;

    @Override
    public String getName() {
        return "open-load-flow-parameters";
    }

    public SlackBusSelectionMode getSlackBusSelectionMode() {
        return slackBusSelectionMode;
    }

    public OpenLoadFlowParameters setSlackBusSelectionMode(SlackBusSelectionMode slackBusSelectionMode) {
        this.slackBusSelectionMode = Objects.requireNonNull(slackBusSelectionMode);
        return this;
    }

    public List<String> getSlackBusesIds() {
        return slackBusesIds;
    }

    public OpenLoadFlowParameters setSlackBusesIds(List<String> slackBusesIds) {
        this.slackBusesIds = Objects.requireNonNull(slackBusesIds);
        return this;
    }

    public OpenLoadFlowParameters setSlackBusId(String slackBusId) {
        this.slackBusesIds = List.of(Objects.requireNonNull(slackBusId));
        return this;
    }

    public boolean isThrowsExceptionInCaseOfSlackDistributionFailure() {
        return throwsExceptionInCaseOfSlackDistributionFailure;
    }

    public OpenLoadFlowParameters setThrowsExceptionInCaseOfSlackDistributionFailure(boolean throwsExceptionInCaseOfSlackDistributionFailure) {
        this.throwsExceptionInCaseOfSlackDistributionFailure = throwsExceptionInCaseOfSlackDistributionFailure;
        return this;
    }

    public boolean hasVoltageRemoteControl() {
        return voltageRemoteControl;
    }

    public OpenLoadFlowParameters setVoltageRemoteControl(boolean voltageRemoteControl) {
        this.voltageRemoteControl = voltageRemoteControl;
        return this;
    }

    public LowImpedanceBranchMode getLowImpedanceBranchMode() {
        return lowImpedanceBranchMode;
    }

    public OpenLoadFlowParameters setLowImpedanceBranchMode(LowImpedanceBranchMode lowImpedanceBranchMode) {
        this.lowImpedanceBranchMode = Objects.requireNonNull(lowImpedanceBranchMode);
        return this;
    }

    public boolean isLoadPowerFactorConstant() {
        return loadPowerFactorConstant;
    }

    public OpenLoadFlowParameters setLoadPowerFactorConstant(boolean loadPowerFactorConstant) {
        this.loadPowerFactorConstant = loadPowerFactorConstant;
        return this;
    }

    public double getPlausibleActivePowerLimit() {
        return plausibleActivePowerLimit;
    }

    public OpenLoadFlowParameters setPlausibleActivePowerLimit(double plausibleActivePowerLimit) {
        if (plausibleActivePowerLimit <= 0) {
            throw new IllegalArgumentException("Invalid plausible active power limit: " + plausibleActivePowerLimit);
        }
        this.plausibleActivePowerLimit = plausibleActivePowerLimit;
        return this;
    }

    public double getSlackBusPMaxMismatch() {
        return slackBusPMaxMismatch;
    }

    public OpenLoadFlowParameters setSlackBusPMaxMismatch(double pSlackBusPMaxMismatch) {
        this.slackBusPMaxMismatch = pSlackBusPMaxMismatch;
        return this;
    }

    public boolean isAddRatioToLinesWithDifferentNominalVoltageAtBothEnds() {
        return addRatioToLinesWithDifferentNominalVoltageAtBothEnds;
    }

    public OpenLoadFlowParameters setAddRatioToLinesWithDifferentNominalVoltageAtBothEnds(boolean addRatioToLinesWithDifferentNominalVoltageAtBothEnds) {
        this.addRatioToLinesWithDifferentNominalVoltageAtBothEnds = addRatioToLinesWithDifferentNominalVoltageAtBothEnds;
        return this;
    }

    public boolean isVoltagePerReactivePowerControl() {
        return voltagePerReactivePowerControl;
    }

    public OpenLoadFlowParameters setVoltagePerReactivePowerControl(boolean voltagePerReactivePowerControl) {
        this.voltagePerReactivePowerControl = voltagePerReactivePowerControl;
        return this;
    }

    public boolean hasReactivePowerRemoteControl() {
        return reactivePowerRemoteControl;
    }

    public OpenLoadFlowParameters setReactivePowerRemoteControl(boolean reactivePowerRemoteControl) {
        this.reactivePowerRemoteControl = reactivePowerRemoteControl;
        return this;
    }

    public int getMaxIteration() {
        return maxIteration;
    }

    public OpenLoadFlowParameters setMaxIteration(int maxIteration) {
        this.maxIteration = NewtonRaphsonParameters.checkMaxIteration(maxIteration);
        return this;
    }

    public double getNewtonRaphsonConvEpsPerEq() {
        return newtonRaphsonConvEpsPerEq;
    }

    public OpenLoadFlowParameters setNewtonRaphsonConvEpsPerEq(double newtonRaphsonConvEpsPerEq) {
        this.newtonRaphsonConvEpsPerEq = newtonRaphsonConvEpsPerEq;
        return this;
    }

    public VoltageInitModeOverride getVoltageInitModeOverride() {
        return voltageInitModeOverride;
    }

    public OpenLoadFlowParameters setVoltageInitModeOverride(VoltageInitModeOverride voltageInitModeOverride) {
        this.voltageInitModeOverride = Objects.requireNonNull(voltageInitModeOverride);
        return this;
    }

    public TransformerVoltageControlMode getTransformerVoltageControlMode() {
        return transformerVoltageControlMode;
    }

    public OpenLoadFlowParameters setTransformerVoltageControlMode(TransformerVoltageControlMode transformerVoltageControlMode) {
        this.transformerVoltageControlMode = Objects.requireNonNull(transformerVoltageControlMode);
        return this;
    }

    public double getDcPowerFactor() {
        return dcPowerFactor;
    }

    public OpenLoadFlowParameters setDcPowerFactor(double dcPowerFactor) {
        this.dcPowerFactor = dcPowerFactor;
        return this;
    }

    public static OpenLoadFlowParameters load() {
        return load(PlatformConfig.defaultConfig());
    }

    public static OpenLoadFlowParameters load(PlatformConfig platformConfig) {
        OpenLoadFlowParameters parameters = new OpenLoadFlowParameters();
        platformConfig.getOptionalModuleConfig("open-loadflow-default-parameters")
            .ifPresent(config -> parameters
                .setSlackBusSelectionMode(config.getEnumProperty(SLACK_BUS_SELECTION_PARAM_NAME, SlackBusSelectionMode.class, SLACK_BUS_SELECTION_DEFAULT_VALUE))
                .setSlackBusesIds(config.getStringListProperty(SLACK_BUSES_IDS_PARAM_NAME, Collections.emptyList()))
                .setLowImpedanceBranchMode(config.getEnumProperty(LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME, LowImpedanceBranchMode.class, LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE))
                .setVoltageRemoteControl(config.getBooleanProperty(VOLTAGE_REMOTE_CONTROL_PARAM_NAME, VOLTAGE_REMOTE_CONTROL_DEFAULT_VALUE))
                .setThrowsExceptionInCaseOfSlackDistributionFailure(
                        config.getBooleanProperty(THROWS_EXCEPTION_IN_CASE_OF_SLACK_DISTRIBUTION_FAILURE_PARAM_NAME, THROWS_EXCEPTION_IN_CASE_OF_SLACK_DISTRIBUTION_FAILURE_DEFAULT_VALUE)
                )
                .setLoadPowerFactorConstant(config.getBooleanProperty(LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME, LOAD_POWER_FACTOR_CONSTANT_DEFAULT_VALUE))
                .setPlausibleActivePowerLimit(config.getDoubleProperty(PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME, LfNetworkParameters.PLAUSIBLE_ACTIVE_POWER_LIMIT_DEFAULT_VALUE))
                .setAddRatioToLinesWithDifferentNominalVoltageAtBothEnds(config.getBooleanProperty(ADD_RATIO_TO_LINES_WITH_DIFFERENT_NOMINAL_VOLTAGE_AT_BOTH_ENDS_NAME, ADD_RATIO_TO_LINES_WITH_DIFFERENT_NOMINAL_VOLTAGE_AT_BOTH_ENDS_DEFAULT_VALUE))
                .setSlackBusPMaxMismatch(config.getDoubleProperty(SLACK_BUS_P_MAX_MISMATCH_NAME, SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE))
                .setVoltagePerReactivePowerControl(config.getBooleanProperty(VOLTAGE_PER_REACTIVE_POWER_CONTROL_NAME, VOLTAGE_PER_REACTIVE_POWER_CONTROL_DEFAULT_VALUE))
                .setReactivePowerRemoteControl(config.getBooleanProperty(REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME, REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE))
                .setMaxIteration(config.getIntProperty(MAX_ITERATION_NAME, NewtonRaphsonParameters.DEFAULT_MAX_ITERATION))
                .setNewtonRaphsonConvEpsPerEq(config.getDoubleProperty(NEWTON_RAPHSON_CONV_EPS_PER_EQ_NAME, DefaultNewtonRaphsonStoppingCriteria.DEFAULT_CONV_EPS_PER_EQ))
                .setVoltageInitModeOverride(config.getEnumProperty(VOLTAGE_INIT_MODE_OVERRIDE_NAME, VoltageInitModeOverride.class, VOLTAGE_INIT_MODE_OVERRIDE_DEFAULT_VALUE))
                .setTransformerVoltageControlMode(config.getEnumProperty(TRANSFORMER_VOLTAGE_CONTROL_MODE_NAME, TransformerVoltageControlMode.class, TRANSFORMER_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE))
                .setDcPowerFactor(config.getDoubleProperty(DC_POWER_FACTOR_NAME, DC_POWER_FACTOR_DEFAULT_VALUE)));
        return parameters;
    }

    public static OpenLoadFlowParameters load(Map<String, String> properties) {
        return new OpenLoadFlowParameters().update(properties);
    }

    public OpenLoadFlowParameters update(Map<String, String> properties) {
        Optional.ofNullable(properties.get(SLACK_BUS_SELECTION_PARAM_NAME))
                .ifPresent(prop -> this.setSlackBusSelectionMode(SlackBusSelectionMode.valueOf(prop)));
        Optional.ofNullable(properties.get(SLACK_BUSES_IDS_PARAM_NAME))
                .ifPresent(prop -> this.setSlackBusesIds(Arrays.asList(prop.split("[:,]"))));
        Optional.ofNullable(properties.get(LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setLowImpedanceBranchMode(LowImpedanceBranchMode.valueOf(prop)));
        Optional.ofNullable(properties.get(VOLTAGE_REMOTE_CONTROL_PARAM_NAME))
                .ifPresent(prop -> this.setVoltageRemoteControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(THROWS_EXCEPTION_IN_CASE_OF_SLACK_DISTRIBUTION_FAILURE_PARAM_NAME))
                .ifPresent(prop -> this.setThrowsExceptionInCaseOfSlackDistributionFailure(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME))
                .ifPresent(prop -> this.setLoadPowerFactorConstant(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME))
                .ifPresent(prop -> this.setPlausibleActivePowerLimit(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(ADD_RATIO_TO_LINES_WITH_DIFFERENT_NOMINAL_VOLTAGE_AT_BOTH_ENDS_NAME))
                .ifPresent(prop -> this.setAddRatioToLinesWithDifferentNominalVoltageAtBothEnds(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(SLACK_BUS_P_MAX_MISMATCH_NAME))
                .ifPresent(prop -> this.setSlackBusPMaxMismatch(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(VOLTAGE_PER_REACTIVE_POWER_CONTROL_NAME))
                .ifPresent(prop -> this.setVoltagePerReactivePowerControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME))
                .ifPresent(prop -> this.setReactivePowerRemoteControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(MAX_ITERATION_NAME))
                .ifPresent(prop -> this.setMaxIteration(Integer.parseInt(prop)));
        Optional.ofNullable(properties.get(NEWTON_RAPHSON_CONV_EPS_PER_EQ_NAME))
                .ifPresent(prop -> this.setNewtonRaphsonConvEpsPerEq(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(VOLTAGE_INIT_MODE_OVERRIDE_NAME))
                .ifPresent(prop -> this.setVoltageInitModeOverride(VoltageInitModeOverride.valueOf(prop)));
        Optional.ofNullable(properties.get(TRANSFORMER_VOLTAGE_CONTROL_MODE_NAME))
                .ifPresent(prop -> this.setTransformerVoltageControlMode(TransformerVoltageControlMode.valueOf(prop)));
        Optional.ofNullable(properties.get(DC_POWER_FACTOR_NAME))
                .ifPresent(prop -> this.setDcPowerFactor(Double.parseDouble(prop)));
        return this;
    }

    @Override
    public String toString() {
        return "OpenLoadFlowParameters(" +
                "slackBusSelectionMode=" + slackBusSelectionMode +
                ", slackBusesIds=" + slackBusesIds +
                ", throwsExceptionInCaseOfSlackDistributionFailure=" + throwsExceptionInCaseOfSlackDistributionFailure +
                ", voltageRemoteControl=" + voltageRemoteControl +
                ", lowImpedanceBranchMode=" + lowImpedanceBranchMode +
                ", loadPowerFactorConstant=" + loadPowerFactorConstant +
                ", plausibleActivePowerLimit=" + plausibleActivePowerLimit +
                ", addRatioToLinesWithDifferentNominalVoltageAtBothEnds=" + addRatioToLinesWithDifferentNominalVoltageAtBothEnds +
                ", slackBusPMaxMismatch=" + slackBusPMaxMismatch +
                ", voltagePerReactivePowerControl=" + voltagePerReactivePowerControl +
                ", reactivePowerRemoteControl=" + reactivePowerRemoteControl +
                ", maxIteration=" + maxIteration +
                ", newtonRaphsonConvEpsPerEq=" + newtonRaphsonConvEpsPerEq +
                ", voltageInitModeOverride=" + voltageInitModeOverride +
                ", transformerVoltageControlMode=" + transformerVoltageControlMode +
                ", dcPowerFactor=" + dcPowerFactor +
                ')';
    }

    public static OpenLoadFlowParameters get(LoadFlowParameters parameters) {
        OpenLoadFlowParameters parametersExt = parameters.getExtension(OpenLoadFlowParameters.class);
        if (parametersExt == null) {
            parametersExt = new OpenLoadFlowParameters();
        }
        return parametersExt;
    }

    private static OpenLoadFlowParameters create(LoadFlowParameters parameters, Supplier<OpenLoadFlowParameters> parametersExtSupplier) {
        Objects.requireNonNull(parameters);
        OpenLoadFlowParameters parametersExt = parametersExtSupplier.get();
        parameters.addExtension(OpenLoadFlowParameters.class, parametersExt);
        return parametersExt;
    }

    public static OpenLoadFlowParameters create(LoadFlowParameters parameters) {
        return create(parameters, OpenLoadFlowParameters::new);
    }

    public static OpenLoadFlowParameters load(LoadFlowParameters parameters) {
        return create(parameters, OpenLoadFlowParameters::load);
    }

    public static void logDc(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        LOGGER.info("Direct current: {}", parameters.isDc());
        LOGGER.info("Slack bus selection mode: {}", parametersExt.getSlackBusSelectionMode());
        LOGGER.info("Use transformer ratio: {}", parameters.isDcUseTransformerRatio());
        LOGGER.info("Distributed slack: {}", parameters.isDistributedSlack());
        LOGGER.info("Balance type: {}", parameters.getBalanceType());
        LOGGER.info("Plausible active power limit: {}", parametersExt.getPlausibleActivePowerLimit());
        LOGGER.info("Add ratio to lines with different nominal voltage at both ends: {}", parametersExt.isAddRatioToLinesWithDifferentNominalVoltageAtBothEnds());
        LOGGER.info("Connected component mode: {}", parameters.getConnectedComponentMode());
        LOGGER.info("DC power factor: {}", parametersExt.getDcPowerFactor());
    }

    /**
     * Log parameters interesting for AC calculation
     */
    public static void logAc(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        LOGGER.info("Direct current: {}", parameters.isDc());
        LOGGER.info("Slack bus selection mode: {}", parametersExt.getSlackBusSelectionMode());
        LOGGER.info("Voltage initialization mode: {}", parameters.getVoltageInitMode());
        LOGGER.info("Voltage initialization mode override: {}", parametersExt.getVoltageInitModeOverride());
        LOGGER.info("Distributed slack: {}", parameters.isDistributedSlack());
        LOGGER.info("Balance type: {}", parameters.getBalanceType());
        LOGGER.info("Reactive limits: {}", !parameters.isNoGeneratorReactiveLimits());
        LOGGER.info("Voltage remote control: {}", parametersExt.hasVoltageRemoteControl());
        LOGGER.info("Phase control: {}", parameters.isPhaseShifterRegulationOn());
        LOGGER.info("Split shunt admittance: {}", parameters.isTwtSplitShuntAdmittance());
        LOGGER.info("Transformer voltage control: {}", parameters.isTransformerVoltageControlOn());
        LOGGER.info("Load power factor constant: {}", parametersExt.isLoadPowerFactorConstant());
        LOGGER.info("Plausible active power limit: {}", parametersExt.getPlausibleActivePowerLimit());
        LOGGER.info("Add ratio to lines with different nominal voltage at both ends: {}", parametersExt.isAddRatioToLinesWithDifferentNominalVoltageAtBothEnds());
        LOGGER.info("Slack bus Pmax mismatch: {}", parametersExt.getSlackBusPMaxMismatch());
        LOGGER.info("Connected component mode: {}", parameters.getConnectedComponentMode());
        LOGGER.info("Voltage per reactive power control: {}", parametersExt.isVoltagePerReactivePowerControl());
        LOGGER.info("Reactive Power Remote control: {}", parametersExt.hasReactivePowerRemoteControl());
        LOGGER.info("Shunt voltage control: {}", parameters.isShuntCompensatorVoltageControlOn());
        LOGGER.info("Hvdc Ac emulation: {}", parameters.isHvdcAcEmulation());
    }

    static VoltageInitializer getVoltageInitializer(LoadFlowParameters parameters, LfNetworkParameters networkParameters, MatrixFactory matrixFactory, Reporter reporter) {
        switch (parameters.getVoltageInitMode()) {
            case UNIFORM_VALUES:
                return new UniformValueVoltageInitializer();
            case PREVIOUS_VALUES:
                return new PreviousValueVoltageInitializer();
            case DC_VALUES:
                return new DcValueVoltageInitializer(networkParameters, parameters.isDistributedSlack(), parameters.getBalanceType(), parameters.isDcUseTransformerRatio(), matrixFactory, reporter);
            default:
                throw new UnsupportedOperationException("Unsupported voltage init mode: " + parameters.getVoltageInitMode());
        }
    }

    static VoltageInitializer getExtendedVoltageInitializer(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                            LfNetworkParameters networkParameters, MatrixFactory matrixFactory, Reporter reporter) {
        switch (parametersExt.getVoltageInitModeOverride()) {
            case NONE:
                return getVoltageInitializer(parameters, networkParameters, matrixFactory, reporter);

            case VOLTAGE_MAGNITUDE:
                return new VoltageMagnitudeInitializer(parameters.isTransformerVoltageControlOn(), matrixFactory);

            case FULL_VOLTAGE:
                return new FullVoltageInitializer(new VoltageMagnitudeInitializer(parameters.isTransformerVoltageControlOn(), matrixFactory),
                        new DcValueVoltageInitializer(networkParameters,
                                                      parameters.isDistributedSlack(),
                                                      parameters.getBalanceType(),
                                                      parameters.isDcUseTransformerRatio(),
                                                      matrixFactory,
                                                      reporter));

            default:
                throw new PowsyblException("Unknown voltage init mode override: " + parametersExt.getVoltageInitModeOverride());
        }
    }

    static LfNetworkParameters getNetworkParameters(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                    SlackBusSelector slackBusSelector, GraphDecrementalConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                    boolean breakers) {
        return new LfNetworkParameters(slackBusSelector,
                                       connectivityFactory,
                                       parametersExt.hasVoltageRemoteControl(),
                                       parametersExt.getLowImpedanceBranchMode() == OpenLoadFlowParameters.LowImpedanceBranchMode.REPLACE_BY_MIN_IMPEDANCE_LINE,
                                       parameters.isTwtSplitShuntAdmittance(),
                                       breakers,
                                       parametersExt.getPlausibleActivePowerLimit(),
                                       parametersExt.isAddRatioToLinesWithDifferentNominalVoltageAtBothEnds(),
                                       parameters.getConnectedComponentMode() == LoadFlowParameters.ConnectedComponentMode.MAIN,
                                       parameters.getCountriesToBalance(),
                                       parameters.isDistributedSlack() && parameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD,
                                       parameters.isPhaseShifterRegulationOn(),
                                       parameters.isTransformerVoltageControlOn(),
                                       parametersExt.isVoltagePerReactivePowerControl(),
                                       parametersExt.hasReactivePowerRemoteControl(),
                                       parameters.isDc(),
                                       parameters.isShuntCompensatorVoltageControlOn(),
                                       !parameters.isNoGeneratorReactiveLimits(),
                                       parameters.isHvdcAcEmulation());
    }

    public static AcLoadFlowParameters createAcParameters(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphDecrementalConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          Reporter reporter) {
        return createAcParameters(network, parameters, parametersExt, matrixFactory, connectivityFactory, reporter, false, false);
    }

    public static AcLoadFlowParameters createAcParameters(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphDecrementalConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          Reporter reporter, boolean breakers, boolean forceA1Var) {
        AcLoadFlowParameters acParameters = createAcParameters(parameters, parametersExt, matrixFactory, connectivityFactory, reporter, breakers, forceA1Var);
        if (parameters.isReadSlackBus()) {
            acParameters.getNetworkParameters().setSlackBusSelector(new NetworkSlackBusSelector(network, acParameters.getNetworkParameters().getSlackBusSelector()));
        }
        return acParameters;
    }

    public static AcLoadFlowParameters createAcParameters(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphDecrementalConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          Reporter reporter, boolean breakers, boolean forceA1Var) {
        SlackBusSelector slackBusSelector = SlackBusSelector.fromMode(parametersExt.getSlackBusSelectionMode(), parametersExt.getSlackBusesIds());

        var networkParameters = getNetworkParameters(parameters, parametersExt, slackBusSelector, connectivityFactory, breakers);

        var equationSystemCreationParameters = new AcEquationSystemCreationParameters(forceA1Var);

        VoltageInitializer voltageInitializer = getExtendedVoltageInitializer(parameters, parametersExt, networkParameters, matrixFactory, reporter);

        var newtonRaphsonParameters = new NewtonRaphsonParameters()
                .setStoppingCriteria(new DefaultNewtonRaphsonStoppingCriteria(parametersExt.getNewtonRaphsonConvEpsPerEq()))
                .setMaxIteration(parametersExt.getMaxIteration());

        OuterLoopConfig outerLoopConfig = OuterLoopConfig.findOuterLoopConfig(new DefaultOuterLoopConfig());
        List<OuterLoop> outerLoops = outerLoopConfig.configure(parameters, parametersExt);

        return new AcLoadFlowParameters(networkParameters,
                                        equationSystemCreationParameters,
                                        newtonRaphsonParameters,
                                        outerLoops,
                                        matrixFactory,
                                        voltageInitializer);
    }

    public static DcLoadFlowParameters createDcParameters(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphDecrementalConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          boolean forcePhaseControlOffAndAddAngle1Var) {
        var dcParameters = createDcParameters(parameters, parametersExt, matrixFactory, connectivityFactory, forcePhaseControlOffAndAddAngle1Var);
        if (parameters.isReadSlackBus()) {
            dcParameters.getNetworkParameters().setSlackBusSelector(new NetworkSlackBusSelector(network, dcParameters.getNetworkParameters().getSlackBusSelector()));
        }
        return dcParameters;
    }

    public static DcLoadFlowParameters createDcParameters(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphDecrementalConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          boolean forcePhaseControlOffAndAddAngle1Var) {
        SlackBusSelector slackBusSelector = SlackBusSelector.fromMode(parametersExt.getSlackBusSelectionMode(), parametersExt.getSlackBusesIds());

        var networkParameters = new LfNetworkParameters(slackBusSelector,
                                                        connectivityFactory,
                                                        false,
                                                        parametersExt.getLowImpedanceBranchMode() == LowImpedanceBranchMode.REPLACE_BY_MIN_IMPEDANCE_LINE,
                                                        false,
                                                        false,
                                                        parametersExt.getPlausibleActivePowerLimit(),
                                                        parametersExt.isAddRatioToLinesWithDifferentNominalVoltageAtBothEnds(),
                                                        parameters.getConnectedComponentMode() == LoadFlowParameters.ConnectedComponentMode.MAIN,
                                                        parameters.getCountriesToBalance(),
                                                        parameters.isDistributedSlack() && parameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD,
                                                        false,
                                                        false,
                                                        false,
                                                        false,
                                                        true,
                                                        false,
                                                        false,
                                                        false); // FIXME

        var equationSystemCreationParameters = new DcEquationSystemCreationParameters(true,
                                                                                      false,
                                                                                      forcePhaseControlOffAndAddAngle1Var,
                                                                                      parameters.isDcUseTransformerRatio());

        return new DcLoadFlowParameters(networkParameters,
                                        equationSystemCreationParameters,
                                        matrixFactory,
                                        parameters.isDistributedSlack(),
                                        parameters.getBalanceType(),
                                        true);
    }
}
