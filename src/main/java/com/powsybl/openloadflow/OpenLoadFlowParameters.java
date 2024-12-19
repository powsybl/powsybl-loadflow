/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.google.common.collect.ImmutableMap;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.extensions.AbstractExtension;
import com.powsybl.commons.parameters.Parameter;
import com.powsybl.commons.parameters.ParameterScope;
import com.powsybl.commons.parameters.ParameterType;
import com.powsybl.iidm.network.Country;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.VoltageMagnitudeInitializer;
import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationParameters;
import com.powsybl.openloadflow.ac.solver.*;
import com.powsybl.openloadflow.ac.outerloop.AcOuterLoop;
import com.powsybl.openloadflow.ac.outerloop.ReactiveLimitsOuterLoop;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcOuterLoop;
import com.powsybl.openloadflow.dc.DcValueVoltageInitializer;
import com.powsybl.openloadflow.dc.equations.DcApproximationType;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import de.vandermeer.asciitable.AsciiTable;
import de.vandermeer.asciitable.CWC_LongestWord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class OpenLoadFlowParameters extends AbstractExtension<LoadFlowParameters> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenLoadFlowParameters.class);

    public static final SlackBusSelectionMode SLACK_BUS_SELECTION_MODE_DEFAULT_VALUE = SlackBusSelectionMode.MOST_MESHED;

    public static final LowImpedanceBranchMode LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE = LowImpedanceBranchMode.REPLACE_BY_ZERO_IMPEDANCE_LINE;

    public static final String MODULE_SPECIFIC_PARAMETERS = "open-loadflow-default-parameters";

    public enum SlackDistributionFailureBehavior {
        THROW,
        FAIL,
        LEAVE_ON_SLACK_BUS,
        DISTRIBUTE_ON_REFERENCE_GENERATOR
    }

    public static final SlackDistributionFailureBehavior SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_DEFAULT_VALUE = SlackDistributionFailureBehavior.LEAVE_ON_SLACK_BUS;

    public static final boolean VOLTAGE_REMOTE_CONTROL_DEFAULT_VALUE = true;

    public static final boolean GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE = false;

    public static final boolean TRANSFORMER_REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE = false;

    public static final boolean LOAD_POWER_FACTOR_CONSTANT_DEFAULT_VALUE = false;

    /**
     * Slack bus maximum active power mismatch in MW: 1 Mw => 10^-2 in p.u
     */
    public static final double SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE = 1.0;

    public static final NewtonRaphsonStoppingCriteriaType NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_DEFAULT_VALUE = NewtonRaphsonStoppingCriteriaType.UNIFORM_CRITERIA;

    /** Default value of the maximum active power mismatch in MW **/
    public static final double MAX_ACTIVE_POWER_MISMATCH_DEFAULT_VALUE = 1e-2;

    /** Default value of the maximum reactive power mismatch in Mvar **/
    public static final double MAX_REACTIVE_POWER_MISMATCH_DEFAULT_VALUE = 1e-2;

    /** Default value of the maximum voltage mismatch in pu **/
    public static final double MAX_VOLTAGE_MISMATCH_DEFAULT_VALUE = 1e-4;

    public static final double MAX_ANGLE_MISMATCH_DEFAULT_VALUE = 1e-5;

    public static final double MAX_RATIO_MISMATCH_DEFAULT_VALUE = 1e-5;

    public static final double MAX_SUSCEPTANCE_MISMATCH_DEFAULT_VALUE = 1e-4;

    public static final boolean VOLTAGE_PER_REACTIVE_POWER_CONTROL_DEFAULT_VALUE = false;

    public static final boolean SVC_VOLTAGE_MONITORING_DEFAULT_VALUE = true;

    public static final VoltageInitModeOverride VOLTAGE_INIT_MODE_OVERRIDE_DEFAULT_VALUE = VoltageInitModeOverride.NONE;

    public static final TransformerVoltageControlMode TRANSFORMER_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE = TransformerVoltageControlMode.WITH_GENERATOR_VOLTAGE_CONTROL;

    public static final ShuntVoltageControlMode SHUNT_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE = ShuntVoltageControlMode.WITH_GENERATOR_VOLTAGE_CONTROL;

    public static final PhaseShifterControlMode PHASE_SHIFTER_CONTROL_MODE_DEFAULT_VALUE = PhaseShifterControlMode.CONTINUOUS_WITH_DISCRETISATION;

    public static final Set<String> ACTIONABLE_SWITCH_IDS_DEFAULT_VALUE = Collections.emptySet();

    public static final Set<String> ACTIONABLE_TRANSFORMERS_IDS_DEFAULT_VALUE = Collections.emptySet();

    public static final Set<ReportedFeatures> REPORTED_FEATURES_DEFAULT_VALUE = Collections.emptySet();

    private static final ReactivePowerDispatchMode REACTIVE_POWER_DISPATCH_MODE_DEFAULT_VALUE = ReactivePowerDispatchMode.Q_EQUAL_PROPORTION;

    protected static final List<String> OUTER_LOOP_NAMES_DEFAULT_VALUE = null;

    protected static final int INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_DEFAULT_VALUE = 3;

    public static final boolean WRITE_REFERENCE_TERMINALS_DEFAULT_VALUE = true;

    protected static final double GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_DEFAULT_VALUE = -1d;

    public enum FictitiousGeneratorVoltageControlCheckMode {
        FORCED,
        NORMAL
    }

    protected static final FictitiousGeneratorVoltageControlCheckMode FICTITIOUS_GENERATOR_VOLTAGE_CONTROL_CHECK_MODE_DEFAULT_VALUE = FictitiousGeneratorVoltageControlCheckMode.FORCED;

    public static final boolean AREA_INTERCHANGE_CONTROL_DEFAULT_VALUE = false;

    public static final double AREA_INTERCHANGE_P_MAX_MISMATCH_DEFAULT_VALUE = 2.0;

    public static final String SLACK_BUS_SELECTION_MODE_PARAM_NAME = "slackBusSelectionMode";

    public static final String SLACK_BUSES_IDS_PARAM_NAME = "slackBusesIds";

    public static final String SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_PARAM_NAME = "slackDistributionFailureBehavior";

    public static final String VOLTAGE_REMOTE_CONTROL_PARAM_NAME = "voltageRemoteControl";

    public static final String GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME = "generatorReactivePowerRemoteControl";

    public static final String TRANSFORMER_REACTIVE_POWER_CONTROL_PARAM_NAME = "transformerReactivePowerControl";

    public static final String LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME = "lowImpedanceBranchMode";

    public static final String LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME = "loadPowerFactorConstant";

    public static final String PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME = "plausibleActivePowerLimit";

    public static final String NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_PARAM_NAME = "newtonRaphsonStoppingCriteriaType";

    public static final String MAX_ACTIVE_POWER_MISMATCH_PARAM_NAME = "maxActivePowerMismatch";

    public static final String MAX_REACTIVE_POWER_MISMATCH_PARAM_NAME = "maxReactivePowerMismatch";

    public static final String MAX_VOLTAGE_MISMATCH_PARAM_NAME = "maxVoltageMismatch";

    public static final String MAX_ANGLE_MISMATCH_PARAM_NAME = "maxAngleMismatch";

    public static final String MAX_RATIO_MISMATCH_PARAM_NAME = "maxRatioMismatch";

    public static final String MAX_SUSCEPTANCE_MISMATCH_PARAM_NAME = "maxSusceptanceMismatch";

    public static final String SLACK_BUS_P_MAX_MISMATCH_PARAM_NAME = "slackBusPMaxMismatch";

    public static final String VOLTAGE_PER_REACTIVE_POWER_CONTROL_PARAM_NAME = "voltagePerReactivePowerControl";

    public static final String MAX_NEWTON_RAPHSON_ITERATIONS_PARAM_NAME = "maxNewtonRaphsonIterations";

    public static final String MAX_OUTER_LOOP_ITERATIONS_PARAM_NAME = "maxOuterLoopIterations";

    public static final String NEWTON_RAPHSON_CONV_EPS_PER_EQ_PARAM_NAME = "newtonRaphsonConvEpsPerEq";

    public static final String VOLTAGE_INIT_MODE_OVERRIDE_PARAM_NAME = "voltageInitModeOverride";

    public static final String TRANSFORMER_VOLTAGE_CONTROL_MODE_PARAM_NAME = "transformerVoltageControlMode";

    public static final String SHUNT_VOLTAGE_CONTROL_MODE_PARAM_NAME = "shuntVoltageControlMode";

    public static final String MIN_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME = "minPlausibleTargetVoltage";

    public static final String MAX_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME = "maxPlausibleTargetVoltage";

    public static final String MIN_REALISTIC_VOLTAGE_PARAM_NAME = "minRealisticVoltage";

    public static final String MAX_REALISTIC_VOLTAGE_PARAM_NAME = "maxRealisticVoltage";

    public static final String MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_PARAM_NAME = "minNominalVoltageTargetVoltageCheck";

    public static final String REACTIVE_RANGE_CHECK_MODE_PARAM_NAME = "reactiveRangeCheckMode";

    public static final String LOW_IMPEDANCE_THRESHOLD_PARAM_NAME = "lowImpedanceThreshold";

    public static final String NETWORK_CACHE_ENABLED_PARAM_NAME = "networkCacheEnabled";

    public static final String SVC_VOLTAGE_MONITORING_PARAM_NAME = "svcVoltageMonitoring";

    public static final String STATE_VECTOR_SCALING_MODE_PARAM_NAME = "stateVectorScalingMode";

    public static final String MAX_SLACK_BUS_COUNT_PARAM_NAME = "maxSlackBusCount";

    public static final String DEBUG_DIR_PARAM_NAME = "debugDir";

    public static final String INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_PARAM_NAME = "incrementalTransformerRatioTapControlOuterLoopMaxTapShift";

    public static final String SECONDARY_VOLTAGE_CONTROL_PARAM_NAME = "secondaryVoltageControl";

    public static final String REACTIVE_LIMITS_MAX_SWITCH_PQ_PV_PARAM_NAME = "reactiveLimitsMaxPqPvSwitch";

    public static final String PHASE_SHIFTER_CONTROL_MODE_PARAM_NAME = "phaseShifterControlMode";

    private static final String ALWAYS_UPDATE_NETWORK_PARAM_NAME = "alwaysUpdateNetwork";

    private static final String MOST_MESHED_SLACK_BUS_SELECTOR_MAX_NOMINAL_VOLTAGE_PERCENTILE_PARAM_NAME = "mostMeshedSlackBusSelectorMaxNominalVoltagePercentile";

    public static final String REPORTED_FEATURES_PARAM_NAME = "reportedFeatures";

    public static final String SLACK_BUS_COUNTRY_FILTER_PARAM_NAME = "slackBusCountryFilter";

    private static final String ACTIONABLE_SWITCHES_IDS_PARAM_NAME = "actionableSwitchesIds";

    private static final String ACTIONABLE_TRANSFORMERS_IDS_PARAM_NAME = "actionableTransformersIds";

    private static final String ASYMMETRICAL_PARAM_NAME = "asymmetrical";

    private static final String REACTIVE_POWER_DISPATCH_MODE_PARAM_NAME = "reactivePowerDispatchMode";

    static final String OUTER_LOOP_NAMES_PARAM_NAME = "outerLoopNames";

    public static final String USE_ACTIVE_LIMITS_PARAM_NAME = "useActiveLimits";

    public static final String DISABLE_VOLTAGE_CONTROL_OF_GENERATORS_OUTSIDE_ACTIVE_POWER_LIMITS_PARAM_NAME = "disableVoltageControlOfGeneratorsOutsideActivePowerLimits";

    private static final String LINE_SEARCH_STATE_VECTOR_SCALING_MAX_ITERATION_PARAM_NAME = "lineSearchStateVectorScalingMaxIteration";

    private static final String LINE_SEARCH_STATE_VECTOR_SCALING_STEP_FOLD_PARAM_NAME = "lineSearchStateVectorScalingStepFold";

    private static final String MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DV_PARAM_NAME = "maxVoltageChangeStateVectorScalingMaxDv";

    private static final String MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DPHI_PARAM_NAME = "maxVoltageChangeStateVectorScalingMaxDphi";

    private static final String LINE_PER_UNIT_MODE_PARAM_NAME = "linePerUnitMode";

    private static final String USE_LOAD_MODEL_PARAM_NAME = "useLoadModel";

    private static final String DC_APPROXIMATION_TYPE_PARAM_NAME = "dcApproximationType";

    public static final String SIMULATE_AUTOMATION_SYSTEMS_PARAM_NAME = "simulateAutomationSystems";

    public static final String AC_SOLVER_TYPE_PARAM_NAME = "acSolverType";

    public static final String MAX_NEWTON_KRYLOV_ITERATIONS_PARAM_NAME = "maxNewtonKrylovIterations";

    public static final String NEWTON_KRYLOV_LINE_SEARCH_PARAM_NAME = "newtonKrylovLineSearch";

    public static final String REFERENCE_BUS_SELECTION_MODE_PARAM_NAME = "referenceBusSelectionMode";

    public static final String WRITE_REFERENCE_TERMINALS_PARAM_NAME = "writeReferenceTerminals";

    public static final String VOLTAGE_TARGET_PRIORITIES_PARAM_NAME = "voltageTargetPriorities";

    public static final String TRANSFORMER_VOLTAGE_CONTROL_USE_INITIAL_TAP_POSITION_PARAM_NAME = "transformerVoltageControlUseInitialTapPosition";

    public static final String GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_PARAM_NAME = "generatorVoltageControlMinNominalVoltage";

    public static final String FICTITIOUS_GENERATOR_VOLTAGE_CONTROL_CHECK_MODE = "fictitiousGeneratorVoltageControlCheckMode";

    public static final String AREA_INTERCHANGE_CONTROL_PARAM_NAME = "areaInterchangeControl";

    public static final String AREA_INTERCHANGE_CONTROL_AREA_TYPE_PARAM_NAME = "areaInterchangeControlAreaType";

    public static final String AREA_INTERCHANGE_P_MAX_MISMATCH_PARAM_NAME = "areaInterchangePMaxMismatch";

    public static <E extends Enum<E>> List<Object> getEnumPossibleValues(Class<E> enumClass) {
        return EnumSet.allOf(enumClass).stream().map(Enum::name).collect(Collectors.toList());
    }

    public static List<Object> getAcSolverTypePossibleValues() {
        return AcSolverFactory.findAll().stream().map(acSolverFactory -> (Object) acSolverFactory.getName()).toList();
    }

    // Category keys
    public static final String MODEL_CATEGORY_KEY = "Model";

    public static final String DC_CATEGORY_KEY = "DC";

    public static final String SLACK_DISTRIBUTION_CATEGORY_KEY = "SlackDistribution";

    public static final String REFERENCE_BUS_CATEGORY_KEY = "ReferenceBus";

    public static final String VOLTAGE_CONTROLS_CATEGORY_KEY = "VoltageControls";

    public static final String GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY = "GeneratorVoltageControl";

    public static final String TRANSFORMER_VOLTAGE_CONTROL_CATEGORY_KEY = "TransformerVoltageControl";

    public static final String SHUNT_VOLTAGE_CONTROL_CATEGORY_KEY = "ShuntVoltageControl";

    public static final String PHASE_CONTROL_CATEGORY_KEY = "PhaseControl";

    public static final String REACTIVE_POWER_CONTROL_CATEGORY_KEY = "ReactivePowerControl";

    public static final String NEWTON_RAPHSON_CATEGORY_KEY = "NewtonRaphson";

    public static final String NEWTON_KRYLOV_CATEGORY_KEY = "NewtonKrylov";

    public static final String FAST_RESTART_CATEGORY_KEY = "FastRestart";

    public static final String OUTER_LOOPS_CATEGORY_KEY = "OuterLoops";

    public static final String SOLVER_CATEGORY_KEY = "Solver";

    public static final String DEBUG_CATEGORY_KEY = "Debug";

    public static final String REPORTING_CATEGORY_KEY = "Reporting";

    public static final String VOLTAGE_INIT_CATEGORY_KEY = "VoltageInit";

    public static final String HVDC_CATEGORY_KEY = "HVDC";

    public static final String AUTOMATION_CATEGORY_KEY = "Automation";

    public static final String PERFORMANCE_CATEGORY_KEY = "Performance";

    public static final Map<String, String> BASE_PARAMETERS_CATEGORY = ImmutableMap.<String, String>builder()
            .put("dc", MODEL_CATEGORY_KEY)
            .put("twtSplitShuntAdmittance", MODEL_CATEGORY_KEY)
            .put("dcPowerFactor", DC_CATEGORY_KEY)
            .put("dcUseTransformerRatio", DC_CATEGORY_KEY)
            .put("useReactiveLimits", VOLTAGE_CONTROLS_CATEGORY_KEY)
            .put("distributedSlack", SLACK_DISTRIBUTION_CATEGORY_KEY)
            .put("readSlackBus", SLACK_DISTRIBUTION_CATEGORY_KEY)
            .put("writeSlackBus", SLACK_DISTRIBUTION_CATEGORY_KEY)
            .put("balanceType", SLACK_DISTRIBUTION_CATEGORY_KEY)
            .put("countriesToBalance", SLACK_DISTRIBUTION_CATEGORY_KEY)
            .put("shuntCompensatorVoltageControlOn", SHUNT_VOLTAGE_CONTROL_CATEGORY_KEY)
            .put("transformerVoltageControlOn", TRANSFORMER_VOLTAGE_CONTROL_CATEGORY_KEY)
            .put("phaseShifterRegulationOn", PHASE_CONTROL_CATEGORY_KEY)
            .put("voltageInitMode", VOLTAGE_INIT_CATEGORY_KEY)
            .put("hvdcAcEmulation", HVDC_CATEGORY_KEY)
            .put("computedConnectedComponentScope", PERFORMANCE_CATEGORY_KEY)
            .build();

    public static final List<Parameter> SPECIFIC_PARAMETERS = List.of(
        new Parameter(SLACK_BUS_SELECTION_MODE_PARAM_NAME, ParameterType.STRING, "Slack bus selection mode", SLACK_BUS_SELECTION_MODE_DEFAULT_VALUE.name(), getEnumPossibleValues(SlackBusSelectionMode.class), ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(SLACK_BUSES_IDS_PARAM_NAME, ParameterType.STRING_LIST, "Slack bus IDs", null, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME, ParameterType.STRING, "Low impedance branch mode", LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE.name(), getEnumPossibleValues(LowImpedanceBranchMode.class), ParameterScope.FUNCTIONAL, MODEL_CATEGORY_KEY),
        new Parameter(VOLTAGE_REMOTE_CONTROL_PARAM_NAME, ParameterType.BOOLEAN, "Generator voltage remote control", VOLTAGE_REMOTE_CONTROL_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_PARAM_NAME, ParameterType.STRING, "Behavior in case of slack distribution failure", SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_DEFAULT_VALUE.name(), getEnumPossibleValues(SlackDistributionFailureBehavior.class), ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME, ParameterType.BOOLEAN, "Load power factor is constant", LOAD_POWER_FACTOR_CONSTANT_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME, ParameterType.DOUBLE, "Plausible active power limit", LfNetworkParameters.PLAUSIBLE_ACTIVE_POWER_LIMIT_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(SLACK_BUS_P_MAX_MISMATCH_PARAM_NAME, ParameterType.DOUBLE, "Slack bus max active power mismatch", SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(VOLTAGE_PER_REACTIVE_POWER_CONTROL_PARAM_NAME, ParameterType.BOOLEAN, "Voltage per reactive power slope", VOLTAGE_PER_REACTIVE_POWER_CONTROL_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME, ParameterType.BOOLEAN, "Generator remote reactive power control", GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, REACTIVE_POWER_CONTROL_CATEGORY_KEY),
        new Parameter(TRANSFORMER_REACTIVE_POWER_CONTROL_PARAM_NAME, ParameterType.BOOLEAN, "Transformer reactive power control", TRANSFORMER_REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, REACTIVE_POWER_CONTROL_CATEGORY_KEY),
        new Parameter(MAX_NEWTON_RAPHSON_ITERATIONS_PARAM_NAME, ParameterType.INTEGER, "Max iterations per Newton-Raphson", NewtonRaphsonParameters.DEFAULT_MAX_ITERATIONS, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_OUTER_LOOP_ITERATIONS_PARAM_NAME, ParameterType.INTEGER, "Max outer loop iterations", AbstractLoadFlowParameters.DEFAULT_MAX_OUTER_LOOP_ITERATIONS, ParameterScope.FUNCTIONAL, OUTER_LOOPS_CATEGORY_KEY),
        new Parameter(NEWTON_RAPHSON_CONV_EPS_PER_EQ_PARAM_NAME, ParameterType.DOUBLE, "Newton-Raphson convergence epsilon per equation", NewtonRaphsonStoppingCriteria.DEFAULT_CONV_EPS_PER_EQ, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(VOLTAGE_INIT_MODE_OVERRIDE_PARAM_NAME, ParameterType.STRING, "Voltage init mode override", VOLTAGE_INIT_MODE_OVERRIDE_DEFAULT_VALUE.name(), getEnumPossibleValues(VoltageInitModeOverride.class), ParameterScope.FUNCTIONAL, VOLTAGE_INIT_CATEGORY_KEY),
        new Parameter(TRANSFORMER_VOLTAGE_CONTROL_MODE_PARAM_NAME, ParameterType.STRING, "Transformer voltage control mode", TRANSFORMER_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE.name(), getEnumPossibleValues(TransformerVoltageControlMode.class), ParameterScope.FUNCTIONAL, TRANSFORMER_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(SHUNT_VOLTAGE_CONTROL_MODE_PARAM_NAME, ParameterType.STRING, "Shunt voltage control mode", SHUNT_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE.name(), getEnumPossibleValues(ShuntVoltageControlMode.class), ParameterScope.FUNCTIONAL, SHUNT_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(MIN_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME, ParameterType.DOUBLE, "Min plausible target voltage", LfNetworkParameters.MIN_PLAUSIBLE_TARGET_VOLTAGE_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, VOLTAGE_CONTROLS_CATEGORY_KEY),
        new Parameter(MAX_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME, ParameterType.DOUBLE, "Max plausible target voltage", LfNetworkParameters.MAX_PLAUSIBLE_TARGET_VOLTAGE_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, VOLTAGE_CONTROLS_CATEGORY_KEY),
        new Parameter(MIN_REALISTIC_VOLTAGE_PARAM_NAME, ParameterType.DOUBLE, "Min realistic voltage", NewtonRaphsonParameters.DEFAULT_MIN_REALISTIC_VOLTAGE, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_REALISTIC_VOLTAGE_PARAM_NAME, ParameterType.DOUBLE, "Max realistic voltage", NewtonRaphsonParameters.DEFAULT_MAX_REALISTIC_VOLTAGE, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(REACTIVE_RANGE_CHECK_MODE_PARAM_NAME, ParameterType.STRING, "Reactive range check mode", LfNetworkParameters.REACTIVE_RANGE_CHECK_MODE_DEFAULT_VALUE.name(), getEnumPossibleValues(ReactiveRangeCheckMode.class), ParameterScope.FUNCTIONAL, GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(LOW_IMPEDANCE_THRESHOLD_PARAM_NAME, ParameterType.DOUBLE, "Low impedance threshold in per unit", LfNetworkParameters.LOW_IMPEDANCE_THRESHOLD_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, MODEL_CATEGORY_KEY),
        new Parameter(NETWORK_CACHE_ENABLED_PARAM_NAME, ParameterType.BOOLEAN, "Network cache enabled", LfNetworkParameters.CACHE_ENABLED_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, FAST_RESTART_CATEGORY_KEY),
        new Parameter(SVC_VOLTAGE_MONITORING_PARAM_NAME, ParameterType.BOOLEAN, "SVC voltage monitoring", SVC_VOLTAGE_MONITORING_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(STATE_VECTOR_SCALING_MODE_PARAM_NAME, ParameterType.STRING, "State vector scaling mode", NewtonRaphsonParameters.DEFAULT_STATE_VECTOR_SCALING_MODE.name(), getEnumPossibleValues(StateVectorScalingMode.class), ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_SLACK_BUS_COUNT_PARAM_NAME, ParameterType.INTEGER, "Maximum slack buses count", LfNetworkParameters.DEFAULT_MAX_SLACK_BUS_COUNT, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(DEBUG_DIR_PARAM_NAME, ParameterType.STRING, "Directory to dump debug files", LfNetworkParameters.DEBUG_DIR_DEFAULT_VALUE, null, ParameterScope.TECHNICAL, DEBUG_CATEGORY_KEY),
        new Parameter(INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_PARAM_NAME, ParameterType.INTEGER, "Incremental transformer ratio tap control maximum tap shift per outer loop", INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, TRANSFORMER_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(SECONDARY_VOLTAGE_CONTROL_PARAM_NAME, ParameterType.BOOLEAN, "Secondary voltage control simulation", LfNetworkParameters.SECONDARY_VOLTAGE_CONTROL_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, VOLTAGE_CONTROLS_CATEGORY_KEY),
        new Parameter(REACTIVE_LIMITS_MAX_SWITCH_PQ_PV_PARAM_NAME, ParameterType.INTEGER, "Reactive limits maximum Pq Pv switch", ReactiveLimitsOuterLoop.MAX_SWITCH_PQ_PV_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_PARAM_NAME, ParameterType.STRING, "Newton-Raphson stopping criteria type", NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_DEFAULT_VALUE.name(), getEnumPossibleValues(NewtonRaphsonStoppingCriteriaType.class), ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_ACTIVE_POWER_MISMATCH_PARAM_NAME, ParameterType.DOUBLE, "Maximum active power for per equation stopping criteria", MAX_ACTIVE_POWER_MISMATCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_REACTIVE_POWER_MISMATCH_PARAM_NAME, ParameterType.DOUBLE, "Maximum reactive power for per equation stopping criteria", MAX_REACTIVE_POWER_MISMATCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_VOLTAGE_MISMATCH_PARAM_NAME, ParameterType.DOUBLE, "Maximum voltage for per equation stopping criteria", MAX_VOLTAGE_MISMATCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_ANGLE_MISMATCH_PARAM_NAME, ParameterType.DOUBLE, "Maximum angle for per equation stopping criteria", MAX_ANGLE_MISMATCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_RATIO_MISMATCH_PARAM_NAME, ParameterType.DOUBLE, "Maximum ratio for per equation stopping criteria", MAX_RATIO_MISMATCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_SUSCEPTANCE_MISMATCH_PARAM_NAME, ParameterType.DOUBLE, "Maximum susceptance for per equation stopping criteria", MAX_SUSCEPTANCE_MISMATCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(PHASE_SHIFTER_CONTROL_MODE_PARAM_NAME, ParameterType.STRING, "Phase shifter control mode", PHASE_SHIFTER_CONTROL_MODE_DEFAULT_VALUE.name(), getEnumPossibleValues(PhaseShifterControlMode.class), ParameterScope.FUNCTIONAL, PHASE_CONTROL_CATEGORY_KEY),
        new Parameter(ALWAYS_UPDATE_NETWORK_PARAM_NAME, ParameterType.BOOLEAN, "Update network even if Newton-Raphson algorithm has diverged", NewtonRaphsonParameters.ALWAYS_UPDATE_NETWORK_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, DEBUG_CATEGORY_KEY),
        new Parameter(MOST_MESHED_SLACK_BUS_SELECTOR_MAX_NOMINAL_VOLTAGE_PERCENTILE_PARAM_NAME, ParameterType.DOUBLE, "In case of most meshed slack bus selection, the max nominal voltage percentile", MostMeshedSlackBusSelector.MAX_NOMINAL_VOLTAGE_PERCENTILE_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(REPORTED_FEATURES_PARAM_NAME, ParameterType.STRING_LIST, "List of extra reported features to be added to report", null, getEnumPossibleValues(ReportedFeatures.class), ParameterScope.FUNCTIONAL, REPORTING_CATEGORY_KEY),
        new Parameter(SLACK_BUS_COUNTRY_FILTER_PARAM_NAME, ParameterType.STRING_LIST, "Slack bus selection country filter (no filtering if empty)", new ArrayList<>(LfNetworkParameters.SLACK_BUS_COUNTRY_FILTER_DEFAULT_VALUE), getEnumPossibleValues(Country.class), ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(ACTIONABLE_SWITCHES_IDS_PARAM_NAME, ParameterType.STRING_LIST, "List of actionable switches IDs (used with fast restart)", new ArrayList<>(ACTIONABLE_SWITCH_IDS_DEFAULT_VALUE), ParameterScope.FUNCTIONAL, FAST_RESTART_CATEGORY_KEY),
        new Parameter(ACTIONABLE_TRANSFORMERS_IDS_PARAM_NAME, ParameterType.STRING_LIST, "List of actionable transformers IDs (used with fast restart for tap position change)", new ArrayList<>(ACTIONABLE_TRANSFORMERS_IDS_DEFAULT_VALUE), ParameterScope.FUNCTIONAL, FAST_RESTART_CATEGORY_KEY),
        new Parameter(ASYMMETRICAL_PARAM_NAME, ParameterType.BOOLEAN, "Asymmetrical calculation", LfNetworkParameters.ASYMMETRICAL_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, MODEL_CATEGORY_KEY),
        new Parameter(MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_PARAM_NAME, ParameterType.DOUBLE, "Min nominal voltage for target voltage check", LfNetworkParameters.MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, VOLTAGE_CONTROLS_CATEGORY_KEY),
        new Parameter(REACTIVE_POWER_DISPATCH_MODE_PARAM_NAME, ParameterType.STRING, "Generators reactive power from bus dispatch mode", REACTIVE_POWER_DISPATCH_MODE_DEFAULT_VALUE.name(), getEnumPossibleValues(ReactivePowerDispatchMode.class), ParameterScope.FUNCTIONAL, GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(OUTER_LOOP_NAMES_PARAM_NAME, ParameterType.STRING_LIST, "Ordered explicit list of outer loop names, supported outer loops are for AC : [" + String.join(", ", ExplicitAcOuterLoopConfig.NAMES) + "], and for DC : [" + String.join(", ", ExplicitDcOuterLoopConfig.NAMES) + "]", OUTER_LOOP_NAMES_DEFAULT_VALUE, ParameterScope.TECHNICAL, OUTER_LOOPS_CATEGORY_KEY),
        new Parameter(USE_ACTIVE_LIMITS_PARAM_NAME, ParameterType.BOOLEAN, "Use active power limits in slack distribution", LfNetworkParameters.USE_ACTIVE_LIMITS_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(DISABLE_VOLTAGE_CONTROL_OF_GENERATORS_OUTSIDE_ACTIVE_POWER_LIMITS_PARAM_NAME, ParameterType.BOOLEAN, "Disable voltage control of generators outside active power limits", LfNetworkParameters.DISABLE_VOLTAGE_CONTROL_OF_GENERATORS_OUTSIDE_ACTIVE_POWER_LIMITS_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(LINE_SEARCH_STATE_VECTOR_SCALING_MAX_ITERATION_PARAM_NAME, ParameterType.INTEGER, "Max iteration for the line search state vector scaling", LineSearchStateVectorScaling.DEFAULT_MAX_ITERATION, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(LINE_SEARCH_STATE_VECTOR_SCALING_STEP_FOLD_PARAM_NAME, ParameterType.DOUBLE, "Step fold for the line search state vector scaling", LineSearchStateVectorScaling.DEFAULT_STEP_FOLD, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DV_PARAM_NAME, ParameterType.DOUBLE, "Max voltage magnitude change for the max voltage change state vector scaling", MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DV, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DPHI_PARAM_NAME, ParameterType.DOUBLE, "Max voltage angle change for the max voltage change state vector scaling", MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DPHI, ParameterScope.FUNCTIONAL, NEWTON_RAPHSON_CATEGORY_KEY),
        new Parameter(LINE_PER_UNIT_MODE_PARAM_NAME, ParameterType.STRING, "Line per unit mode", LinePerUnitMode.IMPEDANCE.name(), getEnumPossibleValues(LinePerUnitMode.class), ParameterScope.FUNCTIONAL, MODEL_CATEGORY_KEY),
        new Parameter(USE_LOAD_MODEL_PARAM_NAME, ParameterType.BOOLEAN, "Use load model (with voltage dependency) for simulation", LfNetworkParameters.USE_LOAD_MODE_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, MODEL_CATEGORY_KEY),
        new Parameter(DC_APPROXIMATION_TYPE_PARAM_NAME, ParameterType.STRING, "DC approximation type", DcEquationSystemCreationParameters.DC_APPROXIMATION_TYPE_DEFAULT_VALUE.name(), getEnumPossibleValues(DcApproximationType.class), ParameterScope.FUNCTIONAL, DC_CATEGORY_KEY),
        new Parameter(SIMULATE_AUTOMATION_SYSTEMS_PARAM_NAME, ParameterType.BOOLEAN, "Automation systems simulation", LfNetworkParameters.SIMULATE_AUTOMATION_SYSTEMS_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, AUTOMATION_CATEGORY_KEY),
        new Parameter(AC_SOLVER_TYPE_PARAM_NAME, ParameterType.STRING, "AC solver type", NewtonRaphsonFactory.NAME, getAcSolverTypePossibleValues(), ParameterScope.FUNCTIONAL, SOLVER_CATEGORY_KEY),
        new Parameter(MAX_NEWTON_KRYLOV_ITERATIONS_PARAM_NAME, ParameterType.INTEGER, "Newton Krylov max number of iterations", NewtonKrylovParameters.DEFAULT_MAX_ITERATIONS, ParameterScope.FUNCTIONAL, NEWTON_KRYLOV_CATEGORY_KEY),
        new Parameter(NEWTON_KRYLOV_LINE_SEARCH_PARAM_NAME, ParameterType.BOOLEAN, "Newton Krylov line search activation", NewtonKrylovParameters.LINE_SEARCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, NEWTON_KRYLOV_CATEGORY_KEY),
        new Parameter(REFERENCE_BUS_SELECTION_MODE_PARAM_NAME, ParameterType.STRING, "Reference bus selection mode", ReferenceBusSelector.DEFAULT_MODE.name(), getEnumPossibleValues(ReferenceBusSelectionMode.class), ParameterScope.FUNCTIONAL, REFERENCE_BUS_CATEGORY_KEY),
        new Parameter(WRITE_REFERENCE_TERMINALS_PARAM_NAME, ParameterType.BOOLEAN, "Write Reference Terminals", WRITE_REFERENCE_TERMINALS_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, REFERENCE_BUS_CATEGORY_KEY),
        new Parameter(VOLTAGE_TARGET_PRIORITIES_PARAM_NAME, ParameterType.STRING_LIST, "Voltage target priorities for voltage controls", LfNetworkParameters.VOLTAGE_CONTROL_PRIORITIES_DEFAULT_VALUE, getEnumPossibleValues(VoltageControl.Type.class), ParameterScope.FUNCTIONAL, VOLTAGE_CONTROLS_CATEGORY_KEY),
        new Parameter(TRANSFORMER_VOLTAGE_CONTROL_USE_INITIAL_TAP_POSITION_PARAM_NAME, ParameterType.BOOLEAN, "Maintain initial tap position if possible", LfNetworkParameters.TRANSFORMER_VOLTAGE_CONTROL_USE_INITIAL_TAP_POSITION_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, TRANSFORMER_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_PARAM_NAME, ParameterType.DOUBLE, "Nominal voltage under which generator voltage controls are disabled during transformer voltage control outer loop of mode AFTER_GENERATOR_VOLTAGE_CONTROL, < 0 means automatic detection", OpenLoadFlowParameters.GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, TRANSFORMER_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(FICTITIOUS_GENERATOR_VOLTAGE_CONTROL_CHECK_MODE, ParameterType.STRING, "Specifies fictitious generators active power checks exemption for voltage control", OpenLoadFlowParameters.FICTITIOUS_GENERATOR_VOLTAGE_CONTROL_CHECK_MODE_DEFAULT_VALUE.name(), getEnumPossibleValues(FictitiousGeneratorVoltageControlCheckMode.class), ParameterScope.FUNCTIONAL, GENERATOR_VOLTAGE_CONTROL_CATEGORY_KEY),
        new Parameter(AREA_INTERCHANGE_CONTROL_PARAM_NAME, ParameterType.BOOLEAN, "Area interchange control", AREA_INTERCHANGE_CONTROL_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(AREA_INTERCHANGE_CONTROL_AREA_TYPE_PARAM_NAME, ParameterType.STRING, "Area type for area interchange control", LfNetworkParameters.AREA_INTERCHANGE_CONTROL_AREA_TYPE_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY),
        new Parameter(AREA_INTERCHANGE_P_MAX_MISMATCH_PARAM_NAME, ParameterType.DOUBLE, "Area interchange max active power mismatch", AREA_INTERCHANGE_P_MAX_MISMATCH_DEFAULT_VALUE, ParameterScope.FUNCTIONAL, SLACK_DISTRIBUTION_CATEGORY_KEY)
    );

    public enum VoltageInitModeOverride {
        NONE,
        VOLTAGE_MAGNITUDE,
        FULL_VOLTAGE
    }

    public enum TransformerVoltageControlMode {
        WITH_GENERATOR_VOLTAGE_CONTROL,
        AFTER_GENERATOR_VOLTAGE_CONTROL,
        INCREMENTAL_VOLTAGE_CONTROL
    }

    public enum ShuntVoltageControlMode {
        WITH_GENERATOR_VOLTAGE_CONTROL,
        INCREMENTAL_VOLTAGE_CONTROL
    }

    public enum PhaseShifterControlMode {
        CONTINUOUS_WITH_DISCRETISATION,
        INCREMENTAL
    }

    public enum ReportedFeatures {
        NEWTON_RAPHSON_LOAD_FLOW,
        NEWTON_RAPHSON_SECURITY_ANALYSIS,
        NEWTON_RAPHSON_SENSITIVITY_ANALYSIS,
    }

    private SlackBusSelectionMode slackBusSelectionMode = SLACK_BUS_SELECTION_MODE_DEFAULT_VALUE;

    private List<String> slackBusesIds = Collections.emptyList();

    private SlackDistributionFailureBehavior slackDistributionFailureBehavior = SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_DEFAULT_VALUE;

    private boolean voltageRemoteControl = VOLTAGE_REMOTE_CONTROL_DEFAULT_VALUE;

    private LowImpedanceBranchMode lowImpedanceBranchMode = LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE;

    public enum LowImpedanceBranchMode {
        REPLACE_BY_ZERO_IMPEDANCE_LINE,
        REPLACE_BY_MIN_IMPEDANCE_LINE
    }

    private boolean loadPowerFactorConstant = LOAD_POWER_FACTOR_CONSTANT_DEFAULT_VALUE;

    private double plausibleActivePowerLimit = LfNetworkParameters.PLAUSIBLE_ACTIVE_POWER_LIMIT_DEFAULT_VALUE;

    private NewtonRaphsonStoppingCriteriaType newtonRaphsonStoppingCriteriaType = NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_DEFAULT_VALUE;

    private double maxActivePowerMismatch = MAX_ACTIVE_POWER_MISMATCH_DEFAULT_VALUE;

    private double maxReactivePowerMismatch = MAX_REACTIVE_POWER_MISMATCH_DEFAULT_VALUE;

    private double maxVoltageMismatch = MAX_VOLTAGE_MISMATCH_DEFAULT_VALUE;

    private double maxAngleMismatch = MAX_ANGLE_MISMATCH_DEFAULT_VALUE;

    private double maxRatioMismatch = MAX_RATIO_MISMATCH_DEFAULT_VALUE;

    private double maxSusceptanceMismatch = MAX_SUSCEPTANCE_MISMATCH_DEFAULT_VALUE;

    private double slackBusPMaxMismatch = SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE;

    private boolean voltagePerReactivePowerControl = VOLTAGE_PER_REACTIVE_POWER_CONTROL_DEFAULT_VALUE;

    private boolean generatorReactivePowerRemoteControl = GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE;

    private boolean transformerReactivePowerControl = TRANSFORMER_REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE;

    private int maxNewtonRaphsonIterations = NewtonRaphsonParameters.DEFAULT_MAX_ITERATIONS;

    private int maxOuterLoopIterations = AbstractLoadFlowParameters.DEFAULT_MAX_OUTER_LOOP_ITERATIONS;

    private double newtonRaphsonConvEpsPerEq = NewtonRaphsonStoppingCriteria.DEFAULT_CONV_EPS_PER_EQ;

    private VoltageInitModeOverride voltageInitModeOverride = VOLTAGE_INIT_MODE_OVERRIDE_DEFAULT_VALUE;

    private TransformerVoltageControlMode transformerVoltageControlMode = TRANSFORMER_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE;

    private ShuntVoltageControlMode shuntVoltageControlMode = SHUNT_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE;

    private double minPlausibleTargetVoltage = LfNetworkParameters.MIN_PLAUSIBLE_TARGET_VOLTAGE_DEFAULT_VALUE;

    private double maxPlausibleTargetVoltage = LfNetworkParameters.MAX_PLAUSIBLE_TARGET_VOLTAGE_DEFAULT_VALUE;

    private double minNominalVoltageTargetVoltageCheck = LfNetworkParameters.MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_DEFAULT_VALUE;

    private double minRealisticVoltage = NewtonRaphsonParameters.DEFAULT_MIN_REALISTIC_VOLTAGE;

    private double maxRealisticVoltage = NewtonRaphsonParameters.DEFAULT_MAX_REALISTIC_VOLTAGE;

    private double lowImpedanceThreshold = LfNetworkParameters.LOW_IMPEDANCE_THRESHOLD_DEFAULT_VALUE;

    public enum ReactiveRangeCheckMode {
        MIN_MAX,
        MAX,
        TARGET_P
    }

    private ReactiveRangeCheckMode reactiveRangeCheckMode = LfNetworkParameters.REACTIVE_RANGE_CHECK_MODE_DEFAULT_VALUE;

    private boolean networkCacheEnabled = LfNetworkParameters.CACHE_ENABLED_DEFAULT_VALUE;

    private boolean svcVoltageMonitoring = SVC_VOLTAGE_MONITORING_DEFAULT_VALUE;

    private StateVectorScalingMode stateVectorScalingMode = NewtonRaphsonParameters.DEFAULT_STATE_VECTOR_SCALING_MODE;

    private int maxSlackBusCount = LfNetworkParameters.DEFAULT_MAX_SLACK_BUS_COUNT;

    private String debugDir = LfNetworkParameters.DEBUG_DIR_DEFAULT_VALUE;

    private int incrementalTransformerRatioTapControlOuterLoopMaxTapShift = INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_DEFAULT_VALUE;

    private boolean secondaryVoltageControl = LfNetworkParameters.SECONDARY_VOLTAGE_CONTROL_DEFAULT_VALUE;

    private int reactiveLimitsMaxPqPvSwitch = ReactiveLimitsOuterLoop.MAX_SWITCH_PQ_PV_DEFAULT_VALUE;

    private PhaseShifterControlMode phaseShifterControlMode = PHASE_SHIFTER_CONTROL_MODE_DEFAULT_VALUE;

    private boolean alwaysUpdateNetwork = NewtonRaphsonParameters.ALWAYS_UPDATE_NETWORK_DEFAULT_VALUE;

    private double mostMeshedSlackBusSelectorMaxNominalVoltagePercentile = MostMeshedSlackBusSelector.MAX_NOMINAL_VOLTAGE_PERCENTILE_DEFAULT_VALUE;

    private Set<ReportedFeatures> reportedFeatures = REPORTED_FEATURES_DEFAULT_VALUE;

    private Set<Country> slackBusCountryFilter = LfNetworkParameters.SLACK_BUS_COUNTRY_FILTER_DEFAULT_VALUE;

    private Set<String> actionableSwitchesIds = ACTIONABLE_SWITCH_IDS_DEFAULT_VALUE;

    private Set<String> actionableTransformersIds = ACTIONABLE_TRANSFORMERS_IDS_DEFAULT_VALUE;

    private boolean asymmetrical = LfNetworkParameters.ASYMMETRICAL_DEFAULT_VALUE;

    private ReactivePowerDispatchMode reactivePowerDispatchMode = REACTIVE_POWER_DISPATCH_MODE_DEFAULT_VALUE;

    private List<String> outerLoopNames = OUTER_LOOP_NAMES_DEFAULT_VALUE;

    private boolean useActiveLimits = LfNetworkParameters.USE_ACTIVE_LIMITS_DEFAULT_VALUE;

    private boolean disableVoltageControlOfGeneratorsOutsideActivePowerLimits = LfNetworkParameters.DISABLE_VOLTAGE_CONTROL_OF_GENERATORS_OUTSIDE_ACTIVE_POWER_LIMITS_DEFAULT_VALUE;

    private int lineSearchStateVectorScalingMaxIteration = LineSearchStateVectorScaling.DEFAULT_MAX_ITERATION;

    private double lineSearchStateVectorScalingStepFold = LineSearchStateVectorScaling.DEFAULT_STEP_FOLD;

    private double maxVoltageChangeStateVectorScalingMaxDv = MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DV;

    private double maxVoltageChangeStateVectorScalingMaxDphi = MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DPHI;

    private LinePerUnitMode linePerUnitMode = LfNetworkParameters.LINE_PER_UNIT_MODE_DEFAULT_VALUE;

    private boolean useLoadModel = LfNetworkParameters.USE_LOAD_MODE_DEFAULT_VALUE;

    private DcApproximationType dcApproximationType = DcEquationSystemCreationParameters.DC_APPROXIMATION_TYPE_DEFAULT_VALUE;

    private boolean simulateAutomationSystems = LfNetworkParameters.SIMULATE_AUTOMATION_SYSTEMS_DEFAULT_VALUE;

    private String acSolverType = NewtonRaphsonFactory.NAME;

    private int maxNewtonKrylovIterations = NewtonKrylovParameters.DEFAULT_MAX_ITERATIONS;

    private boolean newtonKrylovLineSearch = NewtonKrylovParameters.LINE_SEARCH_DEFAULT_VALUE;

    private ReferenceBusSelectionMode referenceBusSelectionMode = ReferenceBusSelector.DEFAULT_MODE;

    private boolean writeReferenceTerminals = WRITE_REFERENCE_TERMINALS_DEFAULT_VALUE;

    private List<String> voltageTargetPriorities = LfNetworkParameters.VOLTAGE_CONTROL_PRIORITIES_DEFAULT_VALUE;

    private boolean transformerVoltageControlUseInitialTapPosition = LfNetworkParameters.TRANSFORMER_VOLTAGE_CONTROL_USE_INITIAL_TAP_POSITION_DEFAULT_VALUE;

    private double generatorVoltageControlMinNominalVoltage = GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_DEFAULT_VALUE;

    private FictitiousGeneratorVoltageControlCheckMode fictitiousGeneratorVoltageControlCheckMode = FICTITIOUS_GENERATOR_VOLTAGE_CONTROL_CHECK_MODE_DEFAULT_VALUE;

    private boolean areaInterchangeControl = AREA_INTERCHANGE_CONTROL_DEFAULT_VALUE;

    private String areaInterchangeControlAreaType = LfNetworkParameters.AREA_INTERCHANGE_CONTROL_AREA_TYPE_DEFAULT_VALUE;

    private double areaInterchangePMaxMismatch = AREA_INTERCHANGE_P_MAX_MISMATCH_DEFAULT_VALUE;

    public static double checkParameterValue(double parameterValue, boolean condition, String parameterName) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid value for parameter " + parameterName + ": " + parameterValue);
        }
        return parameterValue;
    }

    public static int checkParameterValue(int parameterValue, boolean condition, String parameterName) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid value for parameter " + parameterName + ": " + parameterValue);
        }
        return parameterValue;
    }

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

    public SlackDistributionFailureBehavior getSlackDistributionFailureBehavior() {
        return slackDistributionFailureBehavior;
    }

    public OpenLoadFlowParameters setSlackDistributionFailureBehavior(SlackDistributionFailureBehavior slackDistributionFailureBehavior) {
        this.slackDistributionFailureBehavior = Objects.requireNonNull(slackDistributionFailureBehavior);
        return this;
    }

    public boolean isVoltageRemoteControl() {
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

    public OpenLoadFlowParameters setSlackBusPMaxMismatch(double slackBusPMaxMismatch) {
        this.slackBusPMaxMismatch = checkParameterValue(slackBusPMaxMismatch,
                slackBusPMaxMismatch >= 0,
                SLACK_BUS_P_MAX_MISMATCH_PARAM_NAME);
        return this;
    }

    public boolean isVoltagePerReactivePowerControl() {
        return voltagePerReactivePowerControl;
    }

    public OpenLoadFlowParameters setVoltagePerReactivePowerControl(boolean voltagePerReactivePowerControl) {
        this.voltagePerReactivePowerControl = voltagePerReactivePowerControl;
        return this;
    }

    public boolean isGeneratorReactivePowerRemoteControl() {
        return generatorReactivePowerRemoteControl;
    }

    public OpenLoadFlowParameters setGeneratorReactivePowerRemoteControl(boolean generatorReactivePowerRemoteControl) {
        this.generatorReactivePowerRemoteControl = generatorReactivePowerRemoteControl;
        return this;
    }

    public boolean isTransformerReactivePowerControl() {
        return transformerReactivePowerControl;
    }

    public OpenLoadFlowParameters setTransformerReactivePowerControl(boolean transformerReactivePowerControl) {
        this.transformerReactivePowerControl = transformerReactivePowerControl;
        return this;
    }

    public int getMaxNewtonRaphsonIterations() {
        return maxNewtonRaphsonIterations;
    }

    public OpenLoadFlowParameters setMaxNewtonRaphsonIterations(int maxNewtonRaphsonIterations) {
        this.maxNewtonRaphsonIterations = checkParameterValue(maxNewtonRaphsonIterations,
                maxNewtonRaphsonIterations >= 1,
                MAX_NEWTON_RAPHSON_ITERATIONS_PARAM_NAME);
        return this;
    }

    public int getMaxOuterLoopIterations() {
        return maxOuterLoopIterations;
    }

    public OpenLoadFlowParameters setMaxOuterLoopIterations(int maxOuterLoopIterations) {
        this.maxOuterLoopIterations = checkParameterValue(maxOuterLoopIterations,
                maxOuterLoopIterations >= 1,
                MAX_OUTER_LOOP_ITERATIONS_PARAM_NAME);
        return this;
    }

    public double getNewtonRaphsonConvEpsPerEq() {
        return newtonRaphsonConvEpsPerEq;
    }

    public OpenLoadFlowParameters setNewtonRaphsonConvEpsPerEq(double newtonRaphsonConvEpsPerEq) {
        this.newtonRaphsonConvEpsPerEq = checkParameterValue(newtonRaphsonConvEpsPerEq,
                newtonRaphsonConvEpsPerEq > 0,
                NEWTON_RAPHSON_CONV_EPS_PER_EQ_PARAM_NAME);
        return this;
    }

    public NewtonRaphsonStoppingCriteriaType getNewtonRaphsonStoppingCriteriaType() {
        return newtonRaphsonStoppingCriteriaType;
    }

    public OpenLoadFlowParameters setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType newtonRaphsonStoppingCriteriaType) {
        this.newtonRaphsonStoppingCriteriaType = Objects.requireNonNull(newtonRaphsonStoppingCriteriaType);
        return this;
    }

    public double getMaxActivePowerMismatch() {
        return maxActivePowerMismatch;
    }

    public OpenLoadFlowParameters setMaxActivePowerMismatch(double maxActivePowerMismatch) {
        this.maxActivePowerMismatch = checkParameterValue(maxActivePowerMismatch,
                maxActivePowerMismatch > 0,
                MAX_ACTIVE_POWER_MISMATCH_PARAM_NAME);
        return this;
    }

    public double getMaxReactivePowerMismatch() {
        return maxReactivePowerMismatch;
    }

    public OpenLoadFlowParameters setMaxReactivePowerMismatch(double maxReactivePowerMismatch) {
        this.maxReactivePowerMismatch = checkParameterValue(maxReactivePowerMismatch,
                maxReactivePowerMismatch > 0,
                MAX_REACTIVE_POWER_MISMATCH_PARAM_NAME);
        return this;
    }

    public double getMaxVoltageMismatch() {
        return maxVoltageMismatch;
    }

    public OpenLoadFlowParameters setMaxVoltageMismatch(double maxVoltageMismatch) {
        this.maxVoltageMismatch = checkParameterValue(maxVoltageMismatch,
                maxVoltageMismatch > 0,
                MAX_VOLTAGE_MISMATCH_PARAM_NAME);
        return this;
    }

    public double getMaxAngleMismatch() {
        return maxAngleMismatch;
    }

    public OpenLoadFlowParameters setMaxAngleMismatch(double maxAngleMismatch) {
        this.maxAngleMismatch = checkParameterValue(maxAngleMismatch,
                maxAngleMismatch > 0,
                MAX_ANGLE_MISMATCH_PARAM_NAME);
        return this;
    }

    public double getMaxRatioMismatch() {
        return maxRatioMismatch;
    }

    public OpenLoadFlowParameters setMaxRatioMismatch(double maxRatioMismatch) {
        this.maxRatioMismatch = checkParameterValue(maxRatioMismatch,
                maxRatioMismatch > 0,
                MAX_RATIO_MISMATCH_PARAM_NAME);
        return this;
    }

    public double getMaxSusceptanceMismatch() {
        return maxSusceptanceMismatch;
    }

    public OpenLoadFlowParameters setMaxSusceptanceMismatch(double maxSusceptanceMismatch) {
        this.maxSusceptanceMismatch = checkParameterValue(maxSusceptanceMismatch,
                maxSusceptanceMismatch > 0,
                MAX_SUSCEPTANCE_MISMATCH_PARAM_NAME);
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

    public ShuntVoltageControlMode getShuntVoltageControlMode() {
        return shuntVoltageControlMode;
    }

    public OpenLoadFlowParameters setShuntVoltageControlMode(ShuntVoltageControlMode shuntVoltageControlMode) {
        this.shuntVoltageControlMode = Objects.requireNonNull(shuntVoltageControlMode);
        return this;
    }

    public double getMinPlausibleTargetVoltage() {
        return minPlausibleTargetVoltage;
    }

    public OpenLoadFlowParameters setMinPlausibleTargetVoltage(double minPlausibleTargetVoltage) {
        this.minPlausibleTargetVoltage = checkParameterValue(minPlausibleTargetVoltage,
                minPlausibleTargetVoltage >= 0,
                MIN_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME);
        return this;
    }

    public double getMaxPlausibleTargetVoltage() {
        return maxPlausibleTargetVoltage;
    }

    public OpenLoadFlowParameters setMaxPlausibleTargetVoltage(double maxPlausibleTargetVoltage) {
        this.maxPlausibleTargetVoltage = checkParameterValue(maxPlausibleTargetVoltage,
                maxPlausibleTargetVoltage >= 0,
                MAX_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME);
        return this;
    }

    public double getMinNominalVoltageTargetVoltageCheck() {
        return minNominalVoltageTargetVoltageCheck;
    }

    public OpenLoadFlowParameters setMinNominalVoltageTargetVoltageCheck(double minNominalVoltageTargetVoltageCheck) {
        this.minNominalVoltageTargetVoltageCheck = checkParameterValue(minNominalVoltageTargetVoltageCheck,
                minNominalVoltageTargetVoltageCheck >= 0,
                MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_PARAM_NAME);
        return this;
    }

    public double getMinRealisticVoltage() {
        return minRealisticVoltage;
    }

    public OpenLoadFlowParameters setMinRealisticVoltage(double minRealisticVoltage) {
        this.minRealisticVoltage = checkParameterValue(minRealisticVoltage,
                minRealisticVoltage >= 0,
                MIN_REALISTIC_VOLTAGE_PARAM_NAME);
        return this;
    }

    public double getMaxRealisticVoltage() {
        return maxRealisticVoltage;
    }

    public OpenLoadFlowParameters setMaxRealisticVoltage(double maxRealisticVoltage) {
        this.maxRealisticVoltage = checkParameterValue(maxRealisticVoltage,
                maxRealisticVoltage >= 0,
                MAX_REALISTIC_VOLTAGE_PARAM_NAME);
        return this;
    }

    public ReactiveRangeCheckMode getReactiveRangeCheckMode() {
        return reactiveRangeCheckMode;
    }

    public OpenLoadFlowParameters setReactiveRangeCheckMode(ReactiveRangeCheckMode reactiveRangeCheckMode) {
        this.reactiveRangeCheckMode = reactiveRangeCheckMode;
        return this;
    }

    public double getLowImpedanceThreshold() {
        return lowImpedanceThreshold;
    }

    public OpenLoadFlowParameters setLowImpedanceThreshold(double lowImpedanceThreshold) {
        this.lowImpedanceThreshold = checkParameterValue(lowImpedanceThreshold,
                lowImpedanceThreshold > 0,
                LOW_IMPEDANCE_THRESHOLD_PARAM_NAME);
        return this;
    }

    public boolean isNetworkCacheEnabled() {
        return networkCacheEnabled;
    }

    public OpenLoadFlowParameters setNetworkCacheEnabled(boolean networkCacheEnabled) {
        this.networkCacheEnabled = networkCacheEnabled;
        return this;
    }

    public boolean isSvcVoltageMonitoring() {
        return svcVoltageMonitoring;
    }

    public OpenLoadFlowParameters setSvcVoltageMonitoring(boolean svcVoltageMonitoring) {
        this.svcVoltageMonitoring = svcVoltageMonitoring;
        return this;
    }

    public StateVectorScalingMode getStateVectorScalingMode() {
        return stateVectorScalingMode;
    }

    public OpenLoadFlowParameters setStateVectorScalingMode(StateVectorScalingMode stateVectorScalingMode) {
        this.stateVectorScalingMode = Objects.requireNonNull(stateVectorScalingMode);
        return this;
    }

    public int getMaxSlackBusCount() {
        return maxSlackBusCount;
    }

    public OpenLoadFlowParameters setMaxSlackBusCount(int maxSlackBusCount) {
        this.maxSlackBusCount = LfNetworkParameters.checkMaxSlackBusCount(maxSlackBusCount);
        return this;
    }

    public boolean isSecondaryVoltageControl() {
        return secondaryVoltageControl;
    }

    public OpenLoadFlowParameters setSecondaryVoltageControl(boolean secondaryVoltageControl) {
        this.secondaryVoltageControl = secondaryVoltageControl;
        return this;
    }

    public boolean isUseActiveLimits() {
        return useActiveLimits;
    }

    public OpenLoadFlowParameters setUseActiveLimits(boolean useActiveLimits) {
        this.useActiveLimits = useActiveLimits;
        return this;
    }

    public boolean isDisableVoltageControlOfGeneratorsOutsideActivePowerLimits() {
        return disableVoltageControlOfGeneratorsOutsideActivePowerLimits;
    }

    public OpenLoadFlowParameters setDisableVoltageControlOfGeneratorsOutsideActivePowerLimits(boolean disableVoltageControlOfGeneratorsOutsideActivePowerLimits) {
        this.disableVoltageControlOfGeneratorsOutsideActivePowerLimits = disableVoltageControlOfGeneratorsOutsideActivePowerLimits;
        return this;
    }

    public String getDebugDir() {
        return debugDir;
    }

    public OpenLoadFlowParameters setDebugDir(String debugDir) {
        this.debugDir = debugDir;
        return this;
    }

    public int getIncrementalTransformerRatioTapControlOuterLoopMaxTapShift() {
        return incrementalTransformerRatioTapControlOuterLoopMaxTapShift;
    }

    public OpenLoadFlowParameters setIncrementalTransformerRatioTapControlOuterLoopMaxTapShift(int incrementalTransformerRatioTapControlOuterLoopMaxTapShift) {
        this.incrementalTransformerRatioTapControlOuterLoopMaxTapShift = checkParameterValue(incrementalTransformerRatioTapControlOuterLoopMaxTapShift,
                incrementalTransformerRatioTapControlOuterLoopMaxTapShift >= 1,
                INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_PARAM_NAME);
        return this;
    }

    public int getReactiveLimitsMaxPqPvSwitch() {
        return reactiveLimitsMaxPqPvSwitch;
    }

    public OpenLoadFlowParameters setReactiveLimitsMaxPqPvSwitch(int reactiveLimitsMaxPqPvSwitch) {
        this.reactiveLimitsMaxPqPvSwitch = checkParameterValue(reactiveLimitsMaxPqPvSwitch,
                reactiveLimitsMaxPqPvSwitch >= 0,
                REACTIVE_LIMITS_MAX_SWITCH_PQ_PV_PARAM_NAME);
        return this;
    }

    public PhaseShifterControlMode getPhaseShifterControlMode() {
        return phaseShifterControlMode;
    }

    public OpenLoadFlowParameters setPhaseShifterControlMode(PhaseShifterControlMode phaseShifterControlMode) {
        this.phaseShifterControlMode = Objects.requireNonNull(phaseShifterControlMode);
        return this;
    }

    public boolean isAlwaysUpdateNetwork() {
        return alwaysUpdateNetwork;
    }

    public OpenLoadFlowParameters setAlwaysUpdateNetwork(boolean alwaysUpdateNetwork) {
        this.alwaysUpdateNetwork = alwaysUpdateNetwork;
        return this;
    }

    public double getMostMeshedSlackBusSelectorMaxNominalVoltagePercentile() {
        return mostMeshedSlackBusSelectorMaxNominalVoltagePercentile;
    }

    public OpenLoadFlowParameters setMostMeshedSlackBusSelectorMaxNominalVoltagePercentile(double mostMeshedSlackBusSelectorMaxNominalVoltagePercentile) {
        this.mostMeshedSlackBusSelectorMaxNominalVoltagePercentile = checkParameterValue(mostMeshedSlackBusSelectorMaxNominalVoltagePercentile,
                mostMeshedSlackBusSelectorMaxNominalVoltagePercentile >= 0 &&
                        mostMeshedSlackBusSelectorMaxNominalVoltagePercentile <= 100,
                MOST_MESHED_SLACK_BUS_SELECTOR_MAX_NOMINAL_VOLTAGE_PERCENTILE_PARAM_NAME);
        return this;
    }

    public Set<ReportedFeatures> getReportedFeatures() {
        return reportedFeatures;
    }

    public OpenLoadFlowParameters setReportedFeatures(Set<ReportedFeatures> reportedFeatures) {
        this.reportedFeatures = Objects.requireNonNull(reportedFeatures);
        return this;
    }

    public Set<Country> getSlackBusCountryFilter() {
        return slackBusCountryFilter;
    }

    public OpenLoadFlowParameters setSlackBusCountryFilter(Set<Country> slackBusCountryFilter) {
        this.slackBusCountryFilter = Objects.requireNonNull(slackBusCountryFilter);
        return this;
    }

    public Set<String> getActionableSwitchesIds() {
        return actionableSwitchesIds;
    }

    public OpenLoadFlowParameters setActionableSwitchesIds(Set<String> actionableSwitchesIds) {
        this.actionableSwitchesIds = Objects.requireNonNull(actionableSwitchesIds);
        return this;
    }

    public Set<String> getActionableTransformersIds() {
        return actionableTransformersIds;
    }

    public OpenLoadFlowParameters setActionableTransformersIds(Set<String> actionableTransformersIds) {
        this.actionableTransformersIds = Objects.requireNonNull(actionableTransformersIds);
        return this;
    }

    public boolean isAsymmetrical() {
        return asymmetrical;
    }

    public OpenLoadFlowParameters setAsymmetrical(boolean asymmetrical) {
        this.asymmetrical = asymmetrical;
        return this;
    }

    public ReactivePowerDispatchMode getReactivePowerDispatchMode() {
        return reactivePowerDispatchMode;
    }

    public OpenLoadFlowParameters setReactivePowerDispatchMode(ReactivePowerDispatchMode reactivePowerDispatchMode) {
        this.reactivePowerDispatchMode = Objects.requireNonNull(reactivePowerDispatchMode);
        return this;
    }

    public List<String> getOuterLoopNames() {
        return outerLoopNames;
    }

    public OpenLoadFlowParameters setOuterLoopNames(List<String> outerLoopNames) {
        this.outerLoopNames = outerLoopNames;
        return this;
    }

    public int getLineSearchStateVectorScalingMaxIteration() {
        return lineSearchStateVectorScalingMaxIteration;
    }

    public OpenLoadFlowParameters setLineSearchStateVectorScalingMaxIteration(int lineSearchStateVectorScalingMaxIteration) {
        this.lineSearchStateVectorScalingMaxIteration = checkParameterValue(lineSearchStateVectorScalingMaxIteration,
                lineSearchStateVectorScalingMaxIteration >= 1,
                LINE_SEARCH_STATE_VECTOR_SCALING_MAX_ITERATION_PARAM_NAME);
        return this;
    }

    public double getLineSearchStateVectorScalingStepFold() {
        return lineSearchStateVectorScalingStepFold;
    }

    public OpenLoadFlowParameters setLineSearchStateVectorScalingStepFold(double lineSearchStateVectorScalingStepFold) {
        this.lineSearchStateVectorScalingStepFold = checkParameterValue(lineSearchStateVectorScalingStepFold,
                lineSearchStateVectorScalingStepFold > 1,
                LINE_SEARCH_STATE_VECTOR_SCALING_STEP_FOLD_PARAM_NAME);
        return this;
    }

    public double getMaxVoltageChangeStateVectorScalingMaxDv() {
        return maxVoltageChangeStateVectorScalingMaxDv;
    }

    public OpenLoadFlowParameters setMaxVoltageChangeStateVectorScalingMaxDv(double maxVoltageChangeStateVectorScalingMaxDv) {
        this.maxVoltageChangeStateVectorScalingMaxDv = checkParameterValue(maxVoltageChangeStateVectorScalingMaxDv,
                maxVoltageChangeStateVectorScalingMaxDv > 0,
                MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DV_PARAM_NAME);
        return this;
    }

    public double getMaxVoltageChangeStateVectorScalingMaxDphi() {
        return maxVoltageChangeStateVectorScalingMaxDphi;
    }

    public OpenLoadFlowParameters setMaxVoltageChangeStateVectorScalingMaxDphi(double maxVoltageChangeStateVectorScalingMaxDphi) {
        this.maxVoltageChangeStateVectorScalingMaxDphi = checkParameterValue(maxVoltageChangeStateVectorScalingMaxDphi,
                maxVoltageChangeStateVectorScalingMaxDphi > 0,
                MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DPHI_PARAM_NAME);
        return this;
    }

    public LinePerUnitMode getLinePerUnitMode() {
        return linePerUnitMode;
    }

    public OpenLoadFlowParameters setLinePerUnitMode(LinePerUnitMode linePerUnitMode) {
        this.linePerUnitMode = Objects.requireNonNull(linePerUnitMode);
        return this;
    }

    public boolean isUseLoadModel() {
        return useLoadModel;
    }

    public OpenLoadFlowParameters setUseLoadModel(boolean useLoadModel) {
        this.useLoadModel = useLoadModel;
        return this;
    }

    public DcApproximationType getDcApproximationType() {
        return dcApproximationType;
    }

    public OpenLoadFlowParameters setDcApproximationType(DcApproximationType dcApproximationType) {
        this.dcApproximationType = Objects.requireNonNull(dcApproximationType);
        return this;
    }

    public boolean isSimulateAutomationSystems() {
        return simulateAutomationSystems;
    }

    public OpenLoadFlowParameters setSimulateAutomationSystems(boolean simulateAutomationSystems) {
        this.simulateAutomationSystems = simulateAutomationSystems;
        return this;
    }

    public String getAcSolverType() {
        return acSolverType;
    }

    public OpenLoadFlowParameters setAcSolverType(String acSolverType) {
        this.acSolverType = AcSolverFactory.find(acSolverType).getName();
        return this;
    }

    public int getMaxNewtonKrylovIterations() {
        return maxNewtonKrylovIterations;
    }

    public OpenLoadFlowParameters setMaxNewtonKrylovIterations(int maxNewtonKrylovIterations) {
        this.maxNewtonKrylovIterations = checkParameterValue(maxNewtonKrylovIterations,
                maxNewtonKrylovIterations >= 1,
                MAX_NEWTON_KRYLOV_ITERATIONS_PARAM_NAME);
        return this;
    }

    public boolean isNewtonKrylovLineSearch() {
        return newtonKrylovLineSearch;
    }

    public OpenLoadFlowParameters setNewtonKrylovLineSearch(boolean newtonKrylovLineSearch) {
        this.newtonKrylovLineSearch = newtonKrylovLineSearch;
        return this;
    }

    public ReferenceBusSelectionMode getReferenceBusSelectionMode() {
        return referenceBusSelectionMode;
    }

    public OpenLoadFlowParameters setReferenceBusSelectionMode(ReferenceBusSelectionMode referenceBusSelectionMode) {
        this.referenceBusSelectionMode = referenceBusSelectionMode;
        return this;
    }

    public boolean isWriteReferenceTerminals() {
        return writeReferenceTerminals;
    }

    public OpenLoadFlowParameters setWriteReferenceTerminals(boolean writeReferenceTerminals) {
        this.writeReferenceTerminals = writeReferenceTerminals;
        return this;
    }

    public List<String> getVoltageTargetPriorities() {
        return voltageTargetPriorities;
    }

    public OpenLoadFlowParameters setVoltageTargetPriorities(List<String> voltageTargetPriorities) {
        // just check, but do not use return value in this.voltageTargetPriorities:
        // doing this would modify the user's input
        LfNetworkParameters.checkVoltageTargetPriorities(voltageTargetPriorities);
        this.voltageTargetPriorities = voltageTargetPriorities;
        return this;
    }

    public boolean isTransformerVoltageControlUseInitialTapPosition() {
        return transformerVoltageControlUseInitialTapPosition;
    }

    public OpenLoadFlowParameters setTransformerVoltageControlUseInitialTapPosition(boolean transformerVoltageControlUseInitialTapPosition) {
        this.transformerVoltageControlUseInitialTapPosition = transformerVoltageControlUseInitialTapPosition;
        return this;
    }

    /**
     * Only if transformer voltage control is active and with mode `AFTER_GENERATOR_VOLTAGE_CONTROL`. Set the nominal
     * voltage under which the generator voltage control are disabled during outer loop. This parameter overrides the
     * automatic nominal voltage computation if >= 0.
     */
    public OpenLoadFlowParameters setGeneratorVoltageControlMinNominalVoltage(double generatorVoltageControlMinNominalVoltage) {
        this.generatorVoltageControlMinNominalVoltage = generatorVoltageControlMinNominalVoltage;
        return this;
    }

    public double getGeneratorVoltageControlMinNominalVoltage() {
        return generatorVoltageControlMinNominalVoltage;
    }

    public FictitiousGeneratorVoltageControlCheckMode getFictitiousGeneratorVoltageControlCheckMode() {
        return fictitiousGeneratorVoltageControlCheckMode;
    }

    public OpenLoadFlowParameters setFictitiousGeneratorVoltageControlCheckMode(FictitiousGeneratorVoltageControlCheckMode fictitiousGeneratorVoltageControlCheckMode) {
        this.fictitiousGeneratorVoltageControlCheckMode = Objects.requireNonNull(fictitiousGeneratorVoltageControlCheckMode);
        return this;
    }

    public boolean isAreaInterchangeControl() {
        return areaInterchangeControl;
    }

    public OpenLoadFlowParameters setAreaInterchangeControl(boolean areaInterchangeControl) {
        this.areaInterchangeControl = areaInterchangeControl;
        return this;
    }

    public String getAreaInterchangeControlAreaType() {
        return areaInterchangeControlAreaType;
    }

    public OpenLoadFlowParameters setAreaInterchangeControlAreaType(String areaInterchangeControlAreaType) {
        this.areaInterchangeControlAreaType = Objects.requireNonNull(areaInterchangeControlAreaType);
        return this;
    }

    public double getAreaInterchangePMaxMismatch() {
        return areaInterchangePMaxMismatch;
    }

    public OpenLoadFlowParameters setAreaInterchangePMaxMismatch(double areaInterchangePMaxMismatch) {
        this.areaInterchangePMaxMismatch = checkParameterValue(areaInterchangePMaxMismatch,
                areaInterchangePMaxMismatch >= 0,
                AREA_INTERCHANGE_P_MAX_MISMATCH_PARAM_NAME);
        return this;
    }

    public static OpenLoadFlowParameters load() {
        return load(PlatformConfig.defaultConfig());
    }

    public static OpenLoadFlowParameters load(PlatformConfig platformConfig) {
        OpenLoadFlowParameters parameters = new OpenLoadFlowParameters();
        platformConfig.getOptionalModuleConfig(MODULE_SPECIFIC_PARAMETERS)
            .ifPresent(config -> parameters
                .setSlackBusSelectionMode(config.getEnumProperty(SLACK_BUS_SELECTION_MODE_PARAM_NAME, SlackBusSelectionMode.class, SLACK_BUS_SELECTION_MODE_DEFAULT_VALUE))
                .setSlackBusesIds(config.getStringListProperty(SLACK_BUSES_IDS_PARAM_NAME, Collections.emptyList()))
                .setLowImpedanceBranchMode(config.getEnumProperty(LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME, LowImpedanceBranchMode.class, LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE))
                .setVoltageRemoteControl(config.getBooleanProperty(VOLTAGE_REMOTE_CONTROL_PARAM_NAME, VOLTAGE_REMOTE_CONTROL_DEFAULT_VALUE))
                .setSlackDistributionFailureBehavior(config.getEnumProperty(SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_PARAM_NAME, SlackDistributionFailureBehavior.class, SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_DEFAULT_VALUE))
                .setLoadPowerFactorConstant(config.getBooleanProperty(LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME, LOAD_POWER_FACTOR_CONSTANT_DEFAULT_VALUE))
                .setPlausibleActivePowerLimit(config.getDoubleProperty(PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME, LfNetworkParameters.PLAUSIBLE_ACTIVE_POWER_LIMIT_DEFAULT_VALUE))
                .setNewtonRaphsonStoppingCriteriaType(config.getEnumProperty(NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_PARAM_NAME, NewtonRaphsonStoppingCriteriaType.class, NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_DEFAULT_VALUE))
                .setMaxActivePowerMismatch(config.getDoubleProperty(MAX_ACTIVE_POWER_MISMATCH_PARAM_NAME, MAX_ACTIVE_POWER_MISMATCH_DEFAULT_VALUE))
                .setMaxReactivePowerMismatch(config.getDoubleProperty(MAX_REACTIVE_POWER_MISMATCH_PARAM_NAME, MAX_REACTIVE_POWER_MISMATCH_DEFAULT_VALUE))
                .setMaxVoltageMismatch(config.getDoubleProperty(MAX_VOLTAGE_MISMATCH_PARAM_NAME, MAX_VOLTAGE_MISMATCH_DEFAULT_VALUE))
                .setMaxAngleMismatch(config.getDoubleProperty(MAX_ANGLE_MISMATCH_PARAM_NAME, MAX_ANGLE_MISMATCH_DEFAULT_VALUE))
                .setMaxRatioMismatch(config.getDoubleProperty(MAX_RATIO_MISMATCH_PARAM_NAME, MAX_RATIO_MISMATCH_DEFAULT_VALUE))
                .setMaxSusceptanceMismatch(config.getDoubleProperty(MAX_SUSCEPTANCE_MISMATCH_PARAM_NAME, MAX_SUSCEPTANCE_MISMATCH_DEFAULT_VALUE))
                .setSlackBusPMaxMismatch(config.getDoubleProperty(SLACK_BUS_P_MAX_MISMATCH_PARAM_NAME, SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE))
                .setVoltagePerReactivePowerControl(config.getBooleanProperty(VOLTAGE_PER_REACTIVE_POWER_CONTROL_PARAM_NAME, VOLTAGE_PER_REACTIVE_POWER_CONTROL_DEFAULT_VALUE))
                .setGeneratorReactivePowerRemoteControl(config.getBooleanProperty(GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME, GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE))
                .setTransformerReactivePowerControl(config.getBooleanProperty(TRANSFORMER_REACTIVE_POWER_CONTROL_PARAM_NAME, TRANSFORMER_REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE))
                .setMaxNewtonRaphsonIterations(config.getIntProperty(MAX_NEWTON_RAPHSON_ITERATIONS_PARAM_NAME, NewtonRaphsonParameters.DEFAULT_MAX_ITERATIONS))
                .setMaxOuterLoopIterations(config.getIntProperty(MAX_OUTER_LOOP_ITERATIONS_PARAM_NAME, AbstractLoadFlowParameters.DEFAULT_MAX_OUTER_LOOP_ITERATIONS))
                .setNewtonRaphsonConvEpsPerEq(config.getDoubleProperty(NEWTON_RAPHSON_CONV_EPS_PER_EQ_PARAM_NAME, NewtonRaphsonStoppingCriteria.DEFAULT_CONV_EPS_PER_EQ))
                .setVoltageInitModeOverride(config.getEnumProperty(VOLTAGE_INIT_MODE_OVERRIDE_PARAM_NAME, VoltageInitModeOverride.class, VOLTAGE_INIT_MODE_OVERRIDE_DEFAULT_VALUE))
                .setTransformerVoltageControlMode(config.getEnumProperty(TRANSFORMER_VOLTAGE_CONTROL_MODE_PARAM_NAME, TransformerVoltageControlMode.class, TRANSFORMER_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE))
                .setShuntVoltageControlMode(config.getEnumProperty(SHUNT_VOLTAGE_CONTROL_MODE_PARAM_NAME, ShuntVoltageControlMode.class, SHUNT_VOLTAGE_CONTROL_MODE_DEFAULT_VALUE))
                .setMinPlausibleTargetVoltage(config.getDoubleProperty(MIN_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME, LfNetworkParameters.MIN_PLAUSIBLE_TARGET_VOLTAGE_DEFAULT_VALUE))
                .setMaxPlausibleTargetVoltage(config.getDoubleProperty(MAX_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME, LfNetworkParameters.MAX_PLAUSIBLE_TARGET_VOLTAGE_DEFAULT_VALUE))
                .setMinRealisticVoltage(config.getDoubleProperty(MIN_REALISTIC_VOLTAGE_PARAM_NAME, NewtonRaphsonParameters.DEFAULT_MIN_REALISTIC_VOLTAGE))
                .setMaxRealisticVoltage(config.getDoubleProperty(MAX_REALISTIC_VOLTAGE_PARAM_NAME, NewtonRaphsonParameters.DEFAULT_MAX_REALISTIC_VOLTAGE))
                .setReactiveRangeCheckMode(config.getEnumProperty(REACTIVE_RANGE_CHECK_MODE_PARAM_NAME, ReactiveRangeCheckMode.class, LfNetworkParameters.REACTIVE_RANGE_CHECK_MODE_DEFAULT_VALUE))
                .setLowImpedanceThreshold(config.getDoubleProperty(LOW_IMPEDANCE_THRESHOLD_PARAM_NAME, LfNetworkParameters.LOW_IMPEDANCE_THRESHOLD_DEFAULT_VALUE))
                .setNetworkCacheEnabled(config.getBooleanProperty(NETWORK_CACHE_ENABLED_PARAM_NAME, LfNetworkParameters.CACHE_ENABLED_DEFAULT_VALUE))
                .setSvcVoltageMonitoring(config.getBooleanProperty(SVC_VOLTAGE_MONITORING_PARAM_NAME, SVC_VOLTAGE_MONITORING_DEFAULT_VALUE))
                .setNetworkCacheEnabled(config.getBooleanProperty(NETWORK_CACHE_ENABLED_PARAM_NAME, LfNetworkParameters.CACHE_ENABLED_DEFAULT_VALUE))
                .setStateVectorScalingMode(config.getEnumProperty(STATE_VECTOR_SCALING_MODE_PARAM_NAME, StateVectorScalingMode.class, NewtonRaphsonParameters.DEFAULT_STATE_VECTOR_SCALING_MODE))
                .setMaxSlackBusCount(config.getIntProperty(MAX_SLACK_BUS_COUNT_PARAM_NAME, LfNetworkParameters.DEFAULT_MAX_SLACK_BUS_COUNT))
                .setDebugDir(config.getStringProperty(DEBUG_DIR_PARAM_NAME, LfNetworkParameters.DEBUG_DIR_DEFAULT_VALUE))
                .setIncrementalTransformerRatioTapControlOuterLoopMaxTapShift(config.getIntProperty(INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_PARAM_NAME, INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_DEFAULT_VALUE))
                .setSecondaryVoltageControl(config.getBooleanProperty(SECONDARY_VOLTAGE_CONTROL_PARAM_NAME, LfNetworkParameters.SECONDARY_VOLTAGE_CONTROL_DEFAULT_VALUE))
                .setReactiveLimitsMaxPqPvSwitch(config.getIntProperty(REACTIVE_LIMITS_MAX_SWITCH_PQ_PV_PARAM_NAME, ReactiveLimitsOuterLoop.MAX_SWITCH_PQ_PV_DEFAULT_VALUE))
                .setPhaseShifterControlMode(config.getEnumProperty(PHASE_SHIFTER_CONTROL_MODE_PARAM_NAME, PhaseShifterControlMode.class, PHASE_SHIFTER_CONTROL_MODE_DEFAULT_VALUE))
                .setAlwaysUpdateNetwork(config.getBooleanProperty(ALWAYS_UPDATE_NETWORK_PARAM_NAME, NewtonRaphsonParameters.ALWAYS_UPDATE_NETWORK_DEFAULT_VALUE))
                .setMostMeshedSlackBusSelectorMaxNominalVoltagePercentile(config.getDoubleProperty(MOST_MESHED_SLACK_BUS_SELECTOR_MAX_NOMINAL_VOLTAGE_PERCENTILE_PARAM_NAME, MostMeshedSlackBusSelector.MAX_NOMINAL_VOLTAGE_PERCENTILE_DEFAULT_VALUE))
                .setReportedFeatures(config.getEnumSetProperty(REPORTED_FEATURES_PARAM_NAME, ReportedFeatures.class, REPORTED_FEATURES_DEFAULT_VALUE))
                .setSlackBusCountryFilter(config.getEnumSetProperty(SLACK_BUS_COUNTRY_FILTER_PARAM_NAME, Country.class, LfNetworkParameters.SLACK_BUS_COUNTRY_FILTER_DEFAULT_VALUE))
                .setActionableSwitchesIds(new HashSet<>(config.getStringListProperty(ACTIONABLE_SWITCHES_IDS_PARAM_NAME, new ArrayList<>(ACTIONABLE_SWITCH_IDS_DEFAULT_VALUE))))
                .setActionableTransformersIds(new HashSet<>(config.getStringListProperty(ACTIONABLE_TRANSFORMERS_IDS_PARAM_NAME, new ArrayList<>(ACTIONABLE_TRANSFORMERS_IDS_DEFAULT_VALUE))))
                .setAsymmetrical(config.getBooleanProperty(ASYMMETRICAL_PARAM_NAME, LfNetworkParameters.ASYMMETRICAL_DEFAULT_VALUE))
                .setMinNominalVoltageTargetVoltageCheck(config.getDoubleProperty(MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_PARAM_NAME, LfNetworkParameters.MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_DEFAULT_VALUE))
                .setReactivePowerDispatchMode(config.getEnumProperty(REACTIVE_POWER_DISPATCH_MODE_PARAM_NAME, ReactivePowerDispatchMode.class, REACTIVE_POWER_DISPATCH_MODE_DEFAULT_VALUE))
                .setOuterLoopNames(config.getStringListProperty(OUTER_LOOP_NAMES_PARAM_NAME, OUTER_LOOP_NAMES_DEFAULT_VALUE))
                .setUseActiveLimits(config.getBooleanProperty(USE_ACTIVE_LIMITS_PARAM_NAME, LfNetworkParameters.USE_ACTIVE_LIMITS_DEFAULT_VALUE))
                .setDisableVoltageControlOfGeneratorsOutsideActivePowerLimits(config.getBooleanProperty(DISABLE_VOLTAGE_CONTROL_OF_GENERATORS_OUTSIDE_ACTIVE_POWER_LIMITS_PARAM_NAME, LfNetworkParameters.DISABLE_VOLTAGE_CONTROL_OF_GENERATORS_OUTSIDE_ACTIVE_POWER_LIMITS_DEFAULT_VALUE))
                .setLineSearchStateVectorScalingMaxIteration(config.getIntProperty(LINE_SEARCH_STATE_VECTOR_SCALING_MAX_ITERATION_PARAM_NAME, LineSearchStateVectorScaling.DEFAULT_MAX_ITERATION))
                .setLineSearchStateVectorScalingStepFold(config.getDoubleProperty(LINE_SEARCH_STATE_VECTOR_SCALING_STEP_FOLD_PARAM_NAME, LineSearchStateVectorScaling.DEFAULT_STEP_FOLD))
                .setMaxVoltageChangeStateVectorScalingMaxDv(config.getDoubleProperty(MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DV_PARAM_NAME, MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DV))
                .setMaxVoltageChangeStateVectorScalingMaxDphi(config.getDoubleProperty(MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DPHI_PARAM_NAME, MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DPHI))
                .setLinePerUnitMode(config.getEnumProperty(LINE_PER_UNIT_MODE_PARAM_NAME, LinePerUnitMode.class, LfNetworkParameters.LINE_PER_UNIT_MODE_DEFAULT_VALUE))
                .setUseLoadModel(config.getBooleanProperty(USE_LOAD_MODEL_PARAM_NAME, LfNetworkParameters.USE_LOAD_MODE_DEFAULT_VALUE))
                .setDcApproximationType(config.getEnumProperty(DC_APPROXIMATION_TYPE_PARAM_NAME, DcApproximationType.class, DcEquationSystemCreationParameters.DC_APPROXIMATION_TYPE_DEFAULT_VALUE))
                .setSimulateAutomationSystems(config.getBooleanProperty(SIMULATE_AUTOMATION_SYSTEMS_PARAM_NAME, LfNetworkParameters.SIMULATE_AUTOMATION_SYSTEMS_DEFAULT_VALUE))
                .setAcSolverType(config.getStringProperty(AC_SOLVER_TYPE_PARAM_NAME, NewtonRaphsonFactory.NAME))
                .setMaxNewtonKrylovIterations(config.getIntProperty(MAX_NEWTON_KRYLOV_ITERATIONS_PARAM_NAME, NewtonKrylovParameters.DEFAULT_MAX_ITERATIONS))
                .setNewtonKrylovLineSearch(config.getBooleanProperty(NEWTON_KRYLOV_LINE_SEARCH_PARAM_NAME, NewtonKrylovParameters.LINE_SEARCH_DEFAULT_VALUE))
                .setReferenceBusSelectionMode(config.getEnumProperty(REFERENCE_BUS_SELECTION_MODE_PARAM_NAME, ReferenceBusSelectionMode.class, ReferenceBusSelector.DEFAULT_MODE))
                .setWriteReferenceTerminals(config.getBooleanProperty(WRITE_REFERENCE_TERMINALS_PARAM_NAME, WRITE_REFERENCE_TERMINALS_DEFAULT_VALUE))
                .setVoltageTargetPriorities(config.getStringListProperty(VOLTAGE_TARGET_PRIORITIES_PARAM_NAME, LfNetworkParameters.VOLTAGE_CONTROL_PRIORITIES_DEFAULT_VALUE))
                .setTransformerVoltageControlUseInitialTapPosition(config.getBooleanProperty(TRANSFORMER_VOLTAGE_CONTROL_USE_INITIAL_TAP_POSITION_PARAM_NAME, LfNetworkParameters.TRANSFORMER_VOLTAGE_CONTROL_USE_INITIAL_TAP_POSITION_DEFAULT_VALUE))
                .setGeneratorVoltageControlMinNominalVoltage(config.getDoubleProperty(GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_PARAM_NAME, GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_DEFAULT_VALUE))
                .setAreaInterchangeControl(config.getBooleanProperty(AREA_INTERCHANGE_CONTROL_PARAM_NAME, AREA_INTERCHANGE_CONTROL_DEFAULT_VALUE))
                .setAreaInterchangeControlAreaType(config.getStringProperty(AREA_INTERCHANGE_CONTROL_AREA_TYPE_PARAM_NAME, LfNetworkParameters.AREA_INTERCHANGE_CONTROL_AREA_TYPE_DEFAULT_VALUE))
                .setAreaInterchangePMaxMismatch(config.getDoubleProperty(AREA_INTERCHANGE_P_MAX_MISMATCH_PARAM_NAME, AREA_INTERCHANGE_P_MAX_MISMATCH_DEFAULT_VALUE)));
        return parameters;
    }

    public static OpenLoadFlowParameters load(Map<String, String> properties) {
        return new OpenLoadFlowParameters().update(properties);
    }

    private static List<String> parseStringListProp(String prop) {
        if (prop.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(prop.split("[:,]"));
    }

    public OpenLoadFlowParameters update(Map<String, String> properties) {
        Optional.ofNullable(properties.get(SLACK_BUS_SELECTION_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setSlackBusSelectionMode(SlackBusSelectionMode.valueOf(prop)));
        Optional.ofNullable(properties.get(SLACK_BUSES_IDS_PARAM_NAME))
                .ifPresent(prop -> this.setSlackBusesIds(parseStringListProp(prop)));
        Optional.ofNullable(properties.get(LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setLowImpedanceBranchMode(LowImpedanceBranchMode.valueOf(prop)));
        Optional.ofNullable(properties.get(VOLTAGE_REMOTE_CONTROL_PARAM_NAME))
                .ifPresent(prop -> this.setVoltageRemoteControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_PARAM_NAME))
                .ifPresent(prop -> this.setSlackDistributionFailureBehavior(SlackDistributionFailureBehavior.valueOf(prop)));
        Optional.ofNullable(properties.get(LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME))
                .ifPresent(prop -> this.setLoadPowerFactorConstant(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME))
                .ifPresent(prop -> this.setPlausibleActivePowerLimit(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_PARAM_NAME))
                .ifPresent(prop -> this.setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.valueOf(prop)));
        Optional.ofNullable(properties.get(MAX_ACTIVE_POWER_MISMATCH_PARAM_NAME))
                .ifPresent(prop -> this.setMaxActivePowerMismatch(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_REACTIVE_POWER_MISMATCH_PARAM_NAME))
                .ifPresent(prop -> this.setMaxReactivePowerMismatch(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_VOLTAGE_MISMATCH_PARAM_NAME))
                .ifPresent(prop -> this.setMaxVoltageMismatch(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_ANGLE_MISMATCH_PARAM_NAME))
                .ifPresent(prop -> this.setMaxAngleMismatch(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_RATIO_MISMATCH_PARAM_NAME))
                .ifPresent(prop -> this.setMaxRatioMismatch(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_SUSCEPTANCE_MISMATCH_PARAM_NAME))
                .ifPresent(prop -> this.setMaxSusceptanceMismatch(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(SLACK_BUS_P_MAX_MISMATCH_PARAM_NAME))
                .ifPresent(prop -> this.setSlackBusPMaxMismatch(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(VOLTAGE_PER_REACTIVE_POWER_CONTROL_PARAM_NAME))
                .ifPresent(prop -> this.setVoltagePerReactivePowerControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME))
                .ifPresent(prop -> this.setGeneratorReactivePowerRemoteControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(TRANSFORMER_REACTIVE_POWER_CONTROL_PARAM_NAME))
                .ifPresent(prop -> this.setTransformerReactivePowerControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(MAX_NEWTON_RAPHSON_ITERATIONS_PARAM_NAME))
                .ifPresent(prop -> this.setMaxNewtonRaphsonIterations(Integer.parseInt(prop)));
        Optional.ofNullable(properties.get(MAX_OUTER_LOOP_ITERATIONS_PARAM_NAME))
                .ifPresent(prop -> this.setMaxOuterLoopIterations(Integer.parseInt(prop)));
        Optional.ofNullable(properties.get(NEWTON_RAPHSON_CONV_EPS_PER_EQ_PARAM_NAME))
                .ifPresent(prop -> this.setNewtonRaphsonConvEpsPerEq(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(VOLTAGE_INIT_MODE_OVERRIDE_PARAM_NAME))
                .ifPresent(prop -> this.setVoltageInitModeOverride(VoltageInitModeOverride.valueOf(prop)));
        Optional.ofNullable(properties.get(TRANSFORMER_VOLTAGE_CONTROL_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setTransformerVoltageControlMode(TransformerVoltageControlMode.valueOf(prop)));
        Optional.ofNullable(properties.get(SHUNT_VOLTAGE_CONTROL_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setShuntVoltageControlMode(ShuntVoltageControlMode.valueOf(prop)));
        Optional.ofNullable(properties.get(MIN_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME))
                .ifPresent(prop -> this.setMinPlausibleTargetVoltage(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME))
                .ifPresent(prop -> this.setMaxPlausibleTargetVoltage(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MIN_REALISTIC_VOLTAGE_PARAM_NAME))
                .ifPresent(prop -> this.setMinRealisticVoltage(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_REALISTIC_VOLTAGE_PARAM_NAME))
                .ifPresent(prop -> this.setMaxRealisticVoltage(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(REACTIVE_RANGE_CHECK_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setReactiveRangeCheckMode(ReactiveRangeCheckMode.valueOf(prop)));
        Optional.ofNullable(properties.get(LOW_IMPEDANCE_THRESHOLD_PARAM_NAME))
                .ifPresent(prop -> this.setLowImpedanceThreshold(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(NETWORK_CACHE_ENABLED_PARAM_NAME))
                .ifPresent(prop -> this.setNetworkCacheEnabled(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(SVC_VOLTAGE_MONITORING_PARAM_NAME))
                .ifPresent(prop -> this.setSvcVoltageMonitoring(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(STATE_VECTOR_SCALING_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setStateVectorScalingMode(StateVectorScalingMode.valueOf(prop)));
        Optional.ofNullable(properties.get(MAX_SLACK_BUS_COUNT_PARAM_NAME))
                .ifPresent(prop -> this.setMaxSlackBusCount(Integer.parseInt(prop)));
        Optional.ofNullable(properties.get(DEBUG_DIR_PARAM_NAME))
                .ifPresent(this::setDebugDir);
        Optional.ofNullable(properties.get(INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_PARAM_NAME))
                .ifPresent(prop -> this.setIncrementalTransformerRatioTapControlOuterLoopMaxTapShift(Integer.parseInt(prop)));
        Optional.ofNullable(properties.get(SECONDARY_VOLTAGE_CONTROL_PARAM_NAME))
                .ifPresent(prop -> this.setSecondaryVoltageControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(REACTIVE_LIMITS_MAX_SWITCH_PQ_PV_PARAM_NAME))
                .ifPresent(prop -> this.setReactiveLimitsMaxPqPvSwitch(Integer.parseInt(prop)));
        Optional.ofNullable(properties.get(PHASE_SHIFTER_CONTROL_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setPhaseShifterControlMode(PhaseShifterControlMode.valueOf(prop)));
        Optional.ofNullable(properties.get(ALWAYS_UPDATE_NETWORK_PARAM_NAME))
                .ifPresent(prop -> this.setAlwaysUpdateNetwork(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(MOST_MESHED_SLACK_BUS_SELECTOR_MAX_NOMINAL_VOLTAGE_PERCENTILE_PARAM_NAME))
                .ifPresent(prop -> this.setMostMeshedSlackBusSelectorMaxNominalVoltagePercentile(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(REPORTED_FEATURES_PARAM_NAME))
                .ifPresent(prop -> this.setReportedFeatures(
                        parseStringListProp(prop).stream()
                        .map(ReportedFeatures::valueOf)
                        .collect(Collectors.toSet())));
        Optional.ofNullable(properties.get(SLACK_BUS_COUNTRY_FILTER_PARAM_NAME))
                .ifPresent(prop -> this.setSlackBusCountryFilter(parseStringListProp(prop).stream().map(Country::valueOf).collect(Collectors.toSet())));
        Optional.ofNullable(properties.get(ACTIONABLE_SWITCHES_IDS_PARAM_NAME))
                .ifPresent(prop -> this.setActionableSwitchesIds(new HashSet<>(parseStringListProp(prop))));
        Optional.ofNullable(properties.get(ACTIONABLE_TRANSFORMERS_IDS_PARAM_NAME))
                .ifPresent(prop -> this.setActionableTransformersIds(new HashSet<>(parseStringListProp(prop))));
        Optional.ofNullable(properties.get(ASYMMETRICAL_PARAM_NAME))
                .ifPresent(prop -> this.setAsymmetrical(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_PARAM_NAME))
                .ifPresent(prop -> this.setMinNominalVoltageTargetVoltageCheck(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(REACTIVE_POWER_DISPATCH_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setReactivePowerDispatchMode(ReactivePowerDispatchMode.valueOf(prop)));
        Optional.ofNullable(properties.get(OUTER_LOOP_NAMES_PARAM_NAME))
                .ifPresent(prop -> this.setOuterLoopNames(parseStringListProp(prop)));
        Optional.ofNullable(properties.get(USE_ACTIVE_LIMITS_PARAM_NAME))
                .ifPresent(prop -> this.setUseActiveLimits(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(DISABLE_VOLTAGE_CONTROL_OF_GENERATORS_OUTSIDE_ACTIVE_POWER_LIMITS_PARAM_NAME))
                .ifPresent(prop -> this.setDisableVoltageControlOfGeneratorsOutsideActivePowerLimits(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(LINE_SEARCH_STATE_VECTOR_SCALING_MAX_ITERATION_PARAM_NAME))
                .ifPresent(prop -> this.setLineSearchStateVectorScalingMaxIteration(Integer.parseInt(prop)));
        Optional.ofNullable(properties.get(LINE_SEARCH_STATE_VECTOR_SCALING_STEP_FOLD_PARAM_NAME))
                .ifPresent(prop -> this.setLineSearchStateVectorScalingStepFold(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DV_PARAM_NAME))
                .ifPresent(prop -> this.setMaxVoltageChangeStateVectorScalingMaxDv(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DPHI_PARAM_NAME))
                .ifPresent(prop -> this.setMaxVoltageChangeStateVectorScalingMaxDphi(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(LINE_PER_UNIT_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setLinePerUnitMode(LinePerUnitMode.valueOf(prop)));
        Optional.ofNullable(properties.get(USE_LOAD_MODEL_PARAM_NAME))
                .ifPresent(prop -> this.setUseLoadModel(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(DC_APPROXIMATION_TYPE_PARAM_NAME))
                .ifPresent(prop -> this.setDcApproximationType(DcApproximationType.valueOf(prop)));
        Optional.ofNullable(properties.get(SIMULATE_AUTOMATION_SYSTEMS_PARAM_NAME))
                .ifPresent(prop -> this.setSimulateAutomationSystems(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(AC_SOLVER_TYPE_PARAM_NAME))
                .ifPresent(this::setAcSolverType);
        Optional.ofNullable(properties.get(MAX_NEWTON_KRYLOV_ITERATIONS_PARAM_NAME))
                .ifPresent(prop -> this.setMaxNewtonKrylovIterations(Integer.parseInt(prop)));
        Optional.ofNullable(properties.get(NEWTON_KRYLOV_LINE_SEARCH_PARAM_NAME))
                .ifPresent(prop -> this.setNewtonKrylovLineSearch(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(REFERENCE_BUS_SELECTION_MODE_PARAM_NAME))
                .ifPresent(prop -> this.setReferenceBusSelectionMode(ReferenceBusSelectionMode.valueOf(prop)));
        Optional.ofNullable(properties.get(WRITE_REFERENCE_TERMINALS_PARAM_NAME))
                .ifPresent(prop -> this.setWriteReferenceTerminals(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(VOLTAGE_TARGET_PRIORITIES_PARAM_NAME))
                .ifPresent(prop -> this.setVoltageTargetPriorities(parseStringListProp(prop)));
        Optional.ofNullable(properties.get(TRANSFORMER_VOLTAGE_CONTROL_USE_INITIAL_TAP_POSITION_PARAM_NAME))
                .ifPresent(prop -> this.setTransformerVoltageControlUseInitialTapPosition(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_PARAM_NAME))
                .ifPresent(prop -> this.setGeneratorVoltageControlMinNominalVoltage(Double.parseDouble(prop)));
        Optional.ofNullable(properties.get(FICTITIOUS_GENERATOR_VOLTAGE_CONTROL_CHECK_MODE))
                .ifPresent(prop -> this.setFictitiousGeneratorVoltageControlCheckMode(FictitiousGeneratorVoltageControlCheckMode.valueOf(prop)));
        Optional.ofNullable(properties.get(AREA_INTERCHANGE_CONTROL_PARAM_NAME))
                .ifPresent(prop -> this.setAreaInterchangeControl(Boolean.parseBoolean(prop)));
        Optional.ofNullable(properties.get(AREA_INTERCHANGE_CONTROL_AREA_TYPE_PARAM_NAME))
                .ifPresent(this::setAreaInterchangeControlAreaType);
        Optional.ofNullable(properties.get(AREA_INTERCHANGE_P_MAX_MISMATCH_PARAM_NAME))
                .ifPresent(prop -> this.setAreaInterchangePMaxMismatch(Double.parseDouble(prop)));
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(71);
        map.put(SLACK_BUS_SELECTION_MODE_PARAM_NAME, slackBusSelectionMode);
        map.put(SLACK_BUSES_IDS_PARAM_NAME, slackBusesIds);
        map.put(SLACK_DISTRIBUTION_FAILURE_BEHAVIOR_PARAM_NAME, slackDistributionFailureBehavior);
        map.put(VOLTAGE_REMOTE_CONTROL_PARAM_NAME, voltageRemoteControl);
        map.put(LOW_IMPEDANCE_BRANCH_MODE_PARAM_NAME, lowImpedanceBranchMode);
        map.put(LOAD_POWER_FACTOR_CONSTANT_PARAM_NAME, loadPowerFactorConstant);
        map.put(PLAUSIBLE_ACTIVE_POWER_LIMIT_PARAM_NAME, plausibleActivePowerLimit);
        map.put(NEWTONRAPHSON_STOPPING_CRITERIA_TYPE_PARAM_NAME, newtonRaphsonStoppingCriteriaType);
        map.put(SLACK_BUS_P_MAX_MISMATCH_PARAM_NAME, slackBusPMaxMismatch);
        map.put(MAX_ACTIVE_POWER_MISMATCH_PARAM_NAME, maxActivePowerMismatch);
        map.put(MAX_REACTIVE_POWER_MISMATCH_PARAM_NAME, maxReactivePowerMismatch);
        map.put(MAX_VOLTAGE_MISMATCH_PARAM_NAME, maxVoltageMismatch);
        map.put(MAX_ANGLE_MISMATCH_PARAM_NAME, maxAngleMismatch);
        map.put(MAX_RATIO_MISMATCH_PARAM_NAME, maxRatioMismatch);
        map.put(MAX_SUSCEPTANCE_MISMATCH_PARAM_NAME, maxSusceptanceMismatch);
        map.put(VOLTAGE_PER_REACTIVE_POWER_CONTROL_PARAM_NAME, voltagePerReactivePowerControl);
        map.put(GENERATOR_REACTIVE_POWER_REMOTE_CONTROL_PARAM_NAME, generatorReactivePowerRemoteControl);
        map.put(TRANSFORMER_REACTIVE_POWER_CONTROL_PARAM_NAME, transformerReactivePowerControl);
        map.put(MAX_NEWTON_RAPHSON_ITERATIONS_PARAM_NAME, maxNewtonRaphsonIterations);
        map.put(MAX_OUTER_LOOP_ITERATIONS_PARAM_NAME, maxOuterLoopIterations);
        map.put(NEWTON_RAPHSON_CONV_EPS_PER_EQ_PARAM_NAME, newtonRaphsonConvEpsPerEq);
        map.put(VOLTAGE_INIT_MODE_OVERRIDE_PARAM_NAME, voltageInitModeOverride);
        map.put(TRANSFORMER_VOLTAGE_CONTROL_MODE_PARAM_NAME, transformerVoltageControlMode);
        map.put(SHUNT_VOLTAGE_CONTROL_MODE_PARAM_NAME, shuntVoltageControlMode);
        map.put(MIN_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME, minPlausibleTargetVoltage);
        map.put(MAX_PLAUSIBLE_TARGET_VOLTAGE_PARAM_NAME, maxPlausibleTargetVoltage);
        map.put(MIN_REALISTIC_VOLTAGE_PARAM_NAME, minRealisticVoltage);
        map.put(MAX_REALISTIC_VOLTAGE_PARAM_NAME, maxRealisticVoltage);
        map.put(REACTIVE_RANGE_CHECK_MODE_PARAM_NAME, reactiveRangeCheckMode);
        map.put(LOW_IMPEDANCE_THRESHOLD_PARAM_NAME, lowImpedanceThreshold);
        map.put(NETWORK_CACHE_ENABLED_PARAM_NAME, networkCacheEnabled);
        map.put(SVC_VOLTAGE_MONITORING_PARAM_NAME, svcVoltageMonitoring);
        map.put(STATE_VECTOR_SCALING_MODE_PARAM_NAME, stateVectorScalingMode);
        map.put(MAX_SLACK_BUS_COUNT_PARAM_NAME, maxSlackBusCount);
        map.put(DEBUG_DIR_PARAM_NAME, debugDir);
        map.put(INCREMENTAL_TRANSFORMER_RATIO_TAP_CONTROL_OUTER_LOOP_MAX_TAP_SHIFT_PARAM_NAME, incrementalTransformerRatioTapControlOuterLoopMaxTapShift);
        map.put(SECONDARY_VOLTAGE_CONTROL_PARAM_NAME, secondaryVoltageControl);
        map.put(REACTIVE_LIMITS_MAX_SWITCH_PQ_PV_PARAM_NAME, reactiveLimitsMaxPqPvSwitch);
        map.put(PHASE_SHIFTER_CONTROL_MODE_PARAM_NAME, phaseShifterControlMode);
        map.put(ALWAYS_UPDATE_NETWORK_PARAM_NAME, alwaysUpdateNetwork);
        map.put(MOST_MESHED_SLACK_BUS_SELECTOR_MAX_NOMINAL_VOLTAGE_PERCENTILE_PARAM_NAME, mostMeshedSlackBusSelectorMaxNominalVoltagePercentile);
        map.put(REPORTED_FEATURES_PARAM_NAME, reportedFeatures);
        map.put(SLACK_BUS_COUNTRY_FILTER_PARAM_NAME, slackBusCountryFilter);
        map.put(ACTIONABLE_SWITCHES_IDS_PARAM_NAME, actionableSwitchesIds);
        map.put(ACTIONABLE_TRANSFORMERS_IDS_PARAM_NAME, actionableTransformersIds);
        map.put(ASYMMETRICAL_PARAM_NAME, asymmetrical);
        map.put(MIN_NOMINAL_VOLTAGE_TARGET_VOLTAGE_CHECK_PARAM_NAME, minNominalVoltageTargetVoltageCheck);
        map.put(REACTIVE_POWER_DISPATCH_MODE_PARAM_NAME, reactivePowerDispatchMode);
        map.put(OUTER_LOOP_NAMES_PARAM_NAME, outerLoopNames);
        map.put(USE_ACTIVE_LIMITS_PARAM_NAME, useActiveLimits);
        map.put(DISABLE_VOLTAGE_CONTROL_OF_GENERATORS_OUTSIDE_ACTIVE_POWER_LIMITS_PARAM_NAME, disableVoltageControlOfGeneratorsOutsideActivePowerLimits);
        map.put(LINE_SEARCH_STATE_VECTOR_SCALING_MAX_ITERATION_PARAM_NAME, lineSearchStateVectorScalingMaxIteration);
        map.put(LINE_SEARCH_STATE_VECTOR_SCALING_STEP_FOLD_PARAM_NAME, lineSearchStateVectorScalingStepFold);
        map.put(MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DV_PARAM_NAME, maxVoltageChangeStateVectorScalingMaxDv);
        map.put(MAX_VOLTAGE_CHANGE_STATE_VECTOR_SCALING_MAX_DPHI_PARAM_NAME, maxVoltageChangeStateVectorScalingMaxDphi);
        map.put(LINE_PER_UNIT_MODE_PARAM_NAME, linePerUnitMode);
        map.put(USE_LOAD_MODEL_PARAM_NAME, useLoadModel);
        map.put(DC_APPROXIMATION_TYPE_PARAM_NAME, dcApproximationType);
        map.put(SIMULATE_AUTOMATION_SYSTEMS_PARAM_NAME, simulateAutomationSystems);
        map.put(AC_SOLVER_TYPE_PARAM_NAME, acSolverType);
        map.put(MAX_NEWTON_KRYLOV_ITERATIONS_PARAM_NAME, maxNewtonKrylovIterations);
        map.put(NEWTON_KRYLOV_LINE_SEARCH_PARAM_NAME, newtonKrylovLineSearch);
        map.put(REFERENCE_BUS_SELECTION_MODE_PARAM_NAME, referenceBusSelectionMode);
        map.put(WRITE_REFERENCE_TERMINALS_PARAM_NAME, writeReferenceTerminals);
        map.put(VOLTAGE_TARGET_PRIORITIES_PARAM_NAME, voltageTargetPriorities);
        map.put(TRANSFORMER_VOLTAGE_CONTROL_USE_INITIAL_TAP_POSITION_PARAM_NAME, transformerVoltageControlUseInitialTapPosition);
        map.put(GENERATOR_VOLTAGE_CONTROL_MIN_NOMINAL_VOLTAGE_PARAM_NAME, generatorVoltageControlMinNominalVoltage);
        map.put(FICTITIOUS_GENERATOR_VOLTAGE_CONTROL_CHECK_MODE, fictitiousGeneratorVoltageControlCheckMode);
        map.put(AREA_INTERCHANGE_CONTROL_PARAM_NAME, areaInterchangeControl);
        map.put(AREA_INTERCHANGE_CONTROL_AREA_TYPE_PARAM_NAME, areaInterchangeControlAreaType);
        map.put(AREA_INTERCHANGE_P_MAX_MISMATCH_PARAM_NAME, areaInterchangePMaxMismatch);
        return map;
    }

    @Override
    public String toString() {
        return "OpenLoadFlowParameters(" + toMap().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")) + ")";
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

    public static void log(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        if (LOGGER.isInfoEnabled()) {
            // build category map
            Map<String, String> categoryByParameterName = new HashMap<>(BASE_PARAMETERS_CATEGORY);
            for (Parameter parameter : OpenLoadFlowParameters.SPECIFIC_PARAMETERS) {
                if (parameter.getCategoryKey() != null) {
                    categoryByParameterName.put(parameter.getName(), parameter.getCategoryKey());
                }
            }

            record CategorizedParameter(String category, String name, Object value) implements Comparable<CategorizedParameter> {
                @Override
                public int compareTo(CategorizedParameter o) {
                    int c = category.compareTo(o.category);
                    if (c == 0) {
                        c = name.compareTo(o.name);
                    }
                    return c;
                }
            }
            Map<String, Object> parametersMap = new HashMap<>();
            parametersMap.putAll(parameters.toMap());
            parametersMap.putAll(parametersExt.toMap());
            Set<CategorizedParameter> categorizedParameters = parametersMap.entrySet()
                    .stream()
                    .map(e -> new CategorizedParameter(categoryByParameterName.getOrDefault(e.getKey(), "None"), e.getKey(), e.getValue()))
                    .collect(Collectors.toCollection(TreeSet::new));

            AsciiTable at = new AsciiTable();
            at.addRule();
            at.addRow("Category", "Name", "Value");
            at.addRule();
            String previousCategory = null;
            for (var p : categorizedParameters) {
                String category = p.category.equals(previousCategory) ? "" : p.category; // to not repeat in the table for each row
                previousCategory = p.category;
                at.addRow(category, p.name, Objects.toString(p.value, ""));
            }
            at.addRule();
            at.getRenderer().setCWC(new CWC_LongestWord());
            at.setPaddingLeftRight(1, 1);
            LOGGER.info("Parameters:\n{}", at.render());
        }
    }

    static VoltageInitializer getVoltageInitializer(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt, LfNetworkParameters networkParameters, MatrixFactory matrixFactory) {
        switch (parameters.getVoltageInitMode()) {
            case UNIFORM_VALUES:
                return new UniformValueVoltageInitializer();
            case PREVIOUS_VALUES:
                return new PreviousValueVoltageInitializer();
            case DC_VALUES:
                return new DcValueVoltageInitializer(networkParameters, parameters.isDistributedSlack() || parametersExt.isAreaInterchangeControl(), parameters.getBalanceType(), parameters.isDcUseTransformerRatio(), parametersExt.getDcApproximationType(), matrixFactory, parametersExt.getMaxOuterLoopIterations());
            default:
                throw new UnsupportedOperationException("Unsupported voltage init mode: " + parameters.getVoltageInitMode());
        }
    }

    static VoltageInitializer getExtendedVoltageInitializer(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                            LfNetworkParameters networkParameters, MatrixFactory matrixFactory) {
        switch (parametersExt.getVoltageInitModeOverride()) {
            case NONE:
                return getVoltageInitializer(parameters, parametersExt, networkParameters, matrixFactory);

            case VOLTAGE_MAGNITUDE:
                return new VoltageMagnitudeInitializer(parameters.isTransformerVoltageControlOn(), matrixFactory, networkParameters.getLowImpedanceThreshold());

            case FULL_VOLTAGE:
                return new FullVoltageInitializer(new VoltageMagnitudeInitializer(parameters.isTransformerVoltageControlOn(), matrixFactory, networkParameters.getLowImpedanceThreshold()),
                        new DcValueVoltageInitializer(networkParameters,
                                                      parameters.isDistributedSlack() || parametersExt.isAreaInterchangeControl(),
                                                      parameters.getBalanceType(),
                                                      parameters.isDcUseTransformerRatio(),
                                                      parametersExt.getDcApproximationType(),
                                                      matrixFactory,
                                                      parametersExt.getMaxOuterLoopIterations()));

            default:
                throw new PowsyblException("Unknown voltage init mode override: " + parametersExt.getVoltageInitModeOverride());
        }
    }

    static LfNetworkParameters getNetworkParameters(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                    SlackBusSelector slackBusSelector, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                    boolean breakers) {
        return new LfNetworkParameters()
                .setSlackBusSelector(slackBusSelector)
                .setConnectivityFactory(connectivityFactory)
                .setGeneratorVoltageRemoteControl(parametersExt.isVoltageRemoteControl())
                .setMinImpedance(parametersExt.getLowImpedanceBranchMode() == OpenLoadFlowParameters.LowImpedanceBranchMode.REPLACE_BY_MIN_IMPEDANCE_LINE)
                .setTwtSplitShuntAdmittance(parameters.isTwtSplitShuntAdmittance())
                .setBreakers(breakers)
                .setPlausibleActivePowerLimit(parametersExt.getPlausibleActivePowerLimit())
                .setUseActiveLimits(parametersExt.isUseActiveLimits())
                .setDisableVoltageControlOfGeneratorsOutsideActivePowerLimits(parametersExt.isDisableVoltageControlOfGeneratorsOutsideActivePowerLimits())
                .setComputeMainConnectedComponentOnly(parameters.getConnectedComponentMode() == LoadFlowParameters.ConnectedComponentMode.MAIN)
                .setCountriesToBalance(parameters.getCountriesToBalance())
                .setDistributedOnConformLoad((parameters.isDistributedSlack() || parametersExt.isAreaInterchangeControl()) && parameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD)
                .setPhaseControl(parameters.isPhaseShifterRegulationOn())
                .setTransformerVoltageControl(parameters.isTransformerVoltageControlOn())
                .setVoltagePerReactivePowerControl(parametersExt.isVoltagePerReactivePowerControl())
                .setGeneratorReactivePowerRemoteControl(parametersExt.isGeneratorReactivePowerRemoteControl())
                .setTransformerReactivePowerControl(parametersExt.isTransformerReactivePowerControl())
                .setLoadFlowModel(parameters.isDc() ? LoadFlowModel.DC : LoadFlowModel.AC)
                .setShuntVoltageControl(parameters.isShuntCompensatorVoltageControlOn())
                .setReactiveLimits(parameters.isUseReactiveLimits())
                .setHvdcAcEmulation(parameters.isHvdcAcEmulation())
                .setMinPlausibleTargetVoltage(parametersExt.getMinPlausibleTargetVoltage())
                .setMaxPlausibleTargetVoltage(parametersExt.getMaxPlausibleTargetVoltage())
                .setReactiveRangeCheckMode(parametersExt.getReactiveRangeCheckMode())
                .setLowImpedanceThreshold(parametersExt.getLowImpedanceThreshold())
                .setSvcVoltageMonitoring(parametersExt.isSvcVoltageMonitoring())
                .setMaxSlackBusCount(parametersExt.getMaxSlackBusCount())
                .setDebugDir(parametersExt.getDebugDir())
                .setSecondaryVoltageControl(parametersExt.isSecondaryVoltageControl())
                .setCacheEnabled(parametersExt.isNetworkCacheEnabled())
                .setAsymmetrical(parametersExt.isAsymmetrical())
                .setMinNominalVoltageTargetVoltageCheck(parametersExt.getMinNominalVoltageTargetVoltageCheck())
                .setLinePerUnitMode(parametersExt.getLinePerUnitMode())
                .setUseLoadModel(parametersExt.isUseLoadModel())
                .setSimulateAutomationSystems(parametersExt.isSimulateAutomationSystems())
                .setReferenceBusSelector(ReferenceBusSelector.fromMode(parametersExt.getReferenceBusSelectionMode()))
                .setVoltageTargetPriorities(parametersExt.getVoltageTargetPriorities())
                .setFictitiousGeneratorVoltageControlCheckMode(parametersExt.getFictitiousGeneratorVoltageControlCheckMode())
                .setAreaInterchangeControl(parametersExt.isAreaInterchangeControl())
                .setAreaInterchangeControlAreaType(parametersExt.getAreaInterchangeControlAreaType());
    }

    public static AcLoadFlowParameters createAcParameters(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory) {
        return createAcParameters(network, parameters, parametersExt, matrixFactory, connectivityFactory, false, false);
    }

    public static AcLoadFlowParameters createAcParameters(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          boolean breakers, boolean forceA1Var) {
        AcLoadFlowParameters acParameters = createAcParameters(parameters, parametersExt, matrixFactory, connectivityFactory, breakers, forceA1Var);
        if (parameters.isReadSlackBus()) {
            acParameters.getNetworkParameters().setSlackBusSelector(new NetworkSlackBusSelector(network, parametersExt.getSlackBusCountryFilter(),
                    acParameters.getNetworkParameters().getSlackBusSelector()));
        }
        return acParameters;
    }

    static List<AcOuterLoop> createAcOuterLoops(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        AcOuterLoopConfig outerLoopConfig = OuterLoopConfig.findOuterLoopConfig(AcOuterLoopConfig.class)
                .orElseGet(() -> parametersExt.getOuterLoopNames() != null ? new ExplicitAcOuterLoopConfig()
                                                                           : new DefaultAcOuterLoopConfig());
        return outerLoopConfig.configure(parameters, parametersExt);
    }

    static List<DcOuterLoop> createDcOuterLoops(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        DcOuterLoopConfig outerLoopConfig = OuterLoopConfig.findOuterLoopConfig(DcOuterLoopConfig.class)
                .orElseGet(() -> parametersExt.getOuterLoopNames() != null ? new ExplicitDcOuterLoopConfig()
                                                                           : new DefaultDcOuterLoopConfig());
        return outerLoopConfig.configure(parameters, parametersExt);
    }

    public static AcLoadFlowParameters createAcParameters(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          boolean breakers, boolean forceA1Var) {
        SlackBusSelector slackBusSelector = SlackBusSelector.fromMode(parametersExt.getSlackBusSelectionMode(), parametersExt.getSlackBusesIds(),
                parametersExt.getPlausibleActivePowerLimit(), parametersExt.getMostMeshedSlackBusSelectorMaxNominalVoltagePercentile(), parametersExt.getSlackBusCountryFilter());

        var networkParameters = getNetworkParameters(parameters, parametersExt, slackBusSelector, connectivityFactory, breakers);

        var equationSystemCreationParameters = new AcEquationSystemCreationParameters(forceA1Var);

        VoltageInitializer voltageInitializer = getExtendedVoltageInitializer(parameters, parametersExt, networkParameters, matrixFactory);

        List<AcOuterLoop> outerLoops = createAcOuterLoops(parameters, parametersExt);

        AcSolverFactory solverFactory = AcSolverFactory.find(parametersExt.getAcSolverType());

        return new AcLoadFlowParameters()
                .setNetworkParameters(networkParameters)
                .setEquationSystemCreationParameters(equationSystemCreationParameters)
                .setOuterLoops(outerLoops)
                .setMaxOuterLoopIterations(parametersExt.getMaxOuterLoopIterations())
                .setMatrixFactory(matrixFactory)
                .setVoltageInitializer(voltageInitializer)
                .setAsymmetrical(parametersExt.isAsymmetrical())
                .setSlackDistributionFailureBehavior(parametersExt.getSlackDistributionFailureBehavior())
                .setSolverFactory(solverFactory, parameters);
    }

    public static DcLoadFlowParameters createDcParameters(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          boolean forcePhaseControlOffAndAddAngle1Var) {
        var dcParameters = createDcParameters(parameters, parametersExt, matrixFactory, connectivityFactory, forcePhaseControlOffAndAddAngle1Var);
        if (parameters.isReadSlackBus()) {
            dcParameters.getNetworkParameters().setSlackBusSelector(new NetworkSlackBusSelector(network, parametersExt.getSlackBusCountryFilter(),
                    dcParameters.getNetworkParameters().getSlackBusSelector()));
        }
        return dcParameters;
    }

    public static DcLoadFlowParameters createDcParameters(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                                                          MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                          boolean forcePhaseControlOffAndAddAngle1Var) {
        SlackBusSelector slackBusSelector = SlackBusSelector.fromMode(parametersExt.getSlackBusSelectionMode(), parametersExt.getSlackBusesIds(),
                parametersExt.getPlausibleActivePowerLimit(), parametersExt.getMostMeshedSlackBusSelectorMaxNominalVoltagePercentile(), parametersExt.getSlackBusCountryFilter());

        var networkParameters = new LfNetworkParameters()
                .setSlackBusSelector(slackBusSelector)
                .setConnectivityFactory(connectivityFactory)
                .setGeneratorVoltageRemoteControl(false)
                .setMinImpedance(parametersExt.getLowImpedanceBranchMode() == OpenLoadFlowParameters.LowImpedanceBranchMode.REPLACE_BY_MIN_IMPEDANCE_LINE)
                .setTwtSplitShuntAdmittance(false)
                .setBreakers(false)
                .setPlausibleActivePowerLimit(parametersExt.getPlausibleActivePowerLimit())
                .setUseActiveLimits(parametersExt.isUseActiveLimits())
                .setDisableVoltageControlOfGeneratorsOutsideActivePowerLimits(parametersExt.isDisableVoltageControlOfGeneratorsOutsideActivePowerLimits())
                .setComputeMainConnectedComponentOnly(parameters.getConnectedComponentMode() == LoadFlowParameters.ConnectedComponentMode.MAIN)
                .setCountriesToBalance(parameters.getCountriesToBalance())
                .setDistributedOnConformLoad((parameters.isDistributedSlack() || parametersExt.isAreaInterchangeControl()) && parameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD)
                .setPhaseControl(parameters.isPhaseShifterRegulationOn())
                .setTransformerVoltageControl(false)
                .setVoltagePerReactivePowerControl(false)
                .setGeneratorReactivePowerRemoteControl(false)
                .setTransformerReactivePowerControl(false)
                .setLoadFlowModel(LoadFlowModel.DC)
                .setShuntVoltageControl(false)
                .setReactiveLimits(false)
                .setHvdcAcEmulation(parameters.isHvdcAcEmulation())
                .setLowImpedanceThreshold(parametersExt.getLowImpedanceThreshold())
                .setSvcVoltageMonitoring(false)
                .setMaxSlackBusCount(parametersExt.getMaxSlackBusCount())
                .setLinePerUnitMode(parametersExt.getLinePerUnitMode())
                .setReferenceBusSelector(ReferenceBusSelector.fromMode(parametersExt.getReferenceBusSelectionMode()))
                .setAreaInterchangeControl(parametersExt.isAreaInterchangeControl())
                .setAreaInterchangeControlAreaType(parametersExt.getAreaInterchangeControlAreaType());

        var equationSystemCreationParameters = new DcEquationSystemCreationParameters()
                .setUpdateFlows(true)
                .setForcePhaseControlOffAndAddAngle1Var(forcePhaseControlOffAndAddAngle1Var)
                .setUseTransformerRatio(parameters.isDcUseTransformerRatio())
                .setDcApproximationType(parametersExt.getDcApproximationType())
                .setDcPowerFactor(parameters.getDcPowerFactor());

        return new DcLoadFlowParameters()
                .setNetworkParameters(networkParameters)
                .setEquationSystemCreationParameters(equationSystemCreationParameters)
                .setSlackDistributionFailureBehavior(parametersExt.getSlackDistributionFailureBehavior())
                .setMatrixFactory(matrixFactory)
                .setDistributedSlack(parameters.isDistributedSlack())
                .setBalanceType(parameters.getBalanceType())
                .setSetVToNan(true)
                .setOuterLoops(createDcOuterLoops(parameters, parametersExt))
                .setMaxOuterLoopIterations(parametersExt.getMaxOuterLoopIterations())
                .setSlackBusPMaxMismatch(parametersExt.getSlackBusPMaxMismatch())
                .setAreaInterchangePMaxMismatch(parametersExt.getAreaInterchangePMaxMismatch());
    }

    public static boolean equals(LoadFlowParameters parameters1, LoadFlowParameters parameters2) {
        Objects.requireNonNull(parameters1);
        Objects.requireNonNull(parameters2);
        boolean equals = parameters1.getVoltageInitMode() == parameters2.getVoltageInitMode() &&
                parameters1.isTransformerVoltageControlOn() == parameters2.isTransformerVoltageControlOn() &&
                parameters1.isUseReactiveLimits() == parameters2.isUseReactiveLimits() &&
                parameters1.isPhaseShifterRegulationOn() == parameters2.isPhaseShifterRegulationOn() &&
                parameters1.isTwtSplitShuntAdmittance() == parameters2.isTwtSplitShuntAdmittance() &&
                parameters1.isShuntCompensatorVoltageControlOn() == parameters2.isShuntCompensatorVoltageControlOn() &&
                parameters1.isReadSlackBus() == parameters2.isReadSlackBus() &&
                parameters1.isWriteSlackBus() == parameters2.isWriteSlackBus() &&
                parameters1.isDc() == parameters2.isDc() &&
                parameters1.isDistributedSlack() == parameters2.isDistributedSlack() &&
                parameters1.getBalanceType() == parameters2.getBalanceType() &&
                parameters1.isDcUseTransformerRatio() == parameters2.isDcUseTransformerRatio() &&
                parameters1.getCountriesToBalance().equals(parameters2.getCountriesToBalance()) &&
                parameters1.getConnectedComponentMode() == parameters2.getConnectedComponentMode() &&
                parameters1.isHvdcAcEmulation() == parameters2.isHvdcAcEmulation() &&
                parameters1.getDcPowerFactor() == parameters2.getDcPowerFactor();
        if (!equals) {
            return false;
        }

        OpenLoadFlowParameters extension1 = parameters1.getExtension(OpenLoadFlowParameters.class);
        OpenLoadFlowParameters extension2 = parameters2.getExtension(OpenLoadFlowParameters.class);
        if (extension1 == null && extension2 == null) {
            return true;
        }
        if (extension1 == null) {
            return false;
        }
        if (extension2 == null) {
            return false;
        }

        return extension1.getSlackBusSelectionMode() == extension2.getSlackBusSelectionMode() &&
                extension1.getSlackBusesIds().equals(extension2.getSlackBusesIds()) &&
                extension1.getSlackDistributionFailureBehavior() == extension2.getSlackDistributionFailureBehavior() &&
                extension1.isVoltageRemoteControl() == extension2.isVoltageRemoteControl() &&
                extension1.getLowImpedanceBranchMode() == extension2.getLowImpedanceBranchMode() &&
                extension1.isLoadPowerFactorConstant() == extension2.isLoadPowerFactorConstant() &&
                extension1.getPlausibleActivePowerLimit() == extension2.getPlausibleActivePowerLimit() &&
                extension1.getSlackBusPMaxMismatch() == extension2.getSlackBusPMaxMismatch() &&
                extension1.isVoltagePerReactivePowerControl() == extension2.isVoltagePerReactivePowerControl() &&
                extension1.isGeneratorReactivePowerRemoteControl() == extension2.isGeneratorReactivePowerRemoteControl() &&
                extension1.isTransformerReactivePowerControl() == extension2.isTransformerReactivePowerControl() &&
                extension1.getMaxNewtonRaphsonIterations() == extension2.getMaxNewtonRaphsonIterations() &&
                extension1.getMaxOuterLoopIterations() == extension2.getMaxOuterLoopIterations() &&
                extension1.getNewtonRaphsonConvEpsPerEq() == extension2.getNewtonRaphsonConvEpsPerEq() &&
                extension1.getVoltageInitModeOverride() == extension2.getVoltageInitModeOverride() &&
                extension1.getTransformerVoltageControlMode() == extension2.getTransformerVoltageControlMode() &&
                extension1.getShuntVoltageControlMode() == extension2.getShuntVoltageControlMode() &&
                extension1.getMinPlausibleTargetVoltage() == extension2.getMinPlausibleTargetVoltage() &&
                extension1.getMaxPlausibleTargetVoltage() == extension2.getMaxPlausibleTargetVoltage() &&
                extension1.getMinRealisticVoltage() == extension2.getMinRealisticVoltage() &&
                extension1.getMaxRealisticVoltage() == extension2.getMaxRealisticVoltage() &&
                extension1.getReactiveRangeCheckMode() == extension2.getReactiveRangeCheckMode() &&
                extension1.getLowImpedanceThreshold() == extension2.getLowImpedanceThreshold() &&
                extension1.isNetworkCacheEnabled() == extension2.isNetworkCacheEnabled() &&
                extension1.isSvcVoltageMonitoring() == extension2.isSvcVoltageMonitoring() &&
                extension1.getStateVectorScalingMode() == extension2.getStateVectorScalingMode() &&
                extension1.getMaxSlackBusCount() == extension2.getMaxSlackBusCount() &&
                Objects.equals(extension1.getDebugDir(), extension2.getDebugDir()) &&
                extension1.getIncrementalTransformerRatioTapControlOuterLoopMaxTapShift() == extension2.getIncrementalTransformerRatioTapControlOuterLoopMaxTapShift() &&
                extension1.isSecondaryVoltageControl() == extension2.isSecondaryVoltageControl() &&
                extension1.getReactiveLimitsMaxPqPvSwitch() == extension2.getReactiveLimitsMaxPqPvSwitch() &&
                extension1.getPhaseShifterControlMode() == extension2.getPhaseShifterControlMode() &&
                extension1.isAlwaysUpdateNetwork() == extension2.isAlwaysUpdateNetwork() &&
                extension1.getMostMeshedSlackBusSelectorMaxNominalVoltagePercentile() == extension2.getMostMeshedSlackBusSelectorMaxNominalVoltagePercentile() &&
                extension1.getReportedFeatures().equals(extension2.getReportedFeatures()) &&
                extension1.getSlackBusCountryFilter().equals(extension2.getSlackBusCountryFilter()) &&
                extension1.getActionableSwitchesIds().equals(extension2.getActionableSwitchesIds()) &&
                extension1.getActionableTransformersIds().equals(extension2.getActionableTransformersIds()) &&
                extension1.isAsymmetrical() == extension2.isAsymmetrical() &&
                extension1.getMinNominalVoltageTargetVoltageCheck() == extension2.getMinNominalVoltageTargetVoltageCheck() &&
                extension1.getReactivePowerDispatchMode() == extension2.getReactivePowerDispatchMode() &&
                Objects.equals(extension1.getOuterLoopNames(), extension2.getOuterLoopNames()) &&
                extension1.isUseActiveLimits() == extension2.isUseActiveLimits() &&
                extension1.isDisableVoltageControlOfGeneratorsOutsideActivePowerLimits() == extension2.isDisableVoltageControlOfGeneratorsOutsideActivePowerLimits() &&
                extension1.getLineSearchStateVectorScalingMaxIteration() == extension2.getLineSearchStateVectorScalingMaxIteration() &&
                extension1.getLineSearchStateVectorScalingStepFold() == extension2.getLineSearchStateVectorScalingStepFold() &&
                extension1.getMaxVoltageChangeStateVectorScalingMaxDv() == extension2.getMaxVoltageChangeStateVectorScalingMaxDv() &&
                extension1.getMaxVoltageChangeStateVectorScalingMaxDphi() == extension2.getMaxVoltageChangeStateVectorScalingMaxDphi() &&
                extension1.getLinePerUnitMode() == extension2.getLinePerUnitMode() &&
                extension1.isUseLoadModel() == extension2.isUseLoadModel() &&
                extension1.getDcApproximationType() == extension2.getDcApproximationType() &&
                extension1.isSimulateAutomationSystems() == extension2.isSimulateAutomationSystems() &&
                extension1.getAcSolverType().equals(extension2.getAcSolverType()) &&
                extension1.getMaxNewtonKrylovIterations() == extension2.getMaxNewtonKrylovIterations() &&
                extension1.isNewtonKrylovLineSearch() == extension2.isNewtonKrylovLineSearch() &&
                extension1.getReferenceBusSelectionMode() == extension2.getReferenceBusSelectionMode() &&
                extension1.isWriteReferenceTerminals() == extension2.isWriteReferenceTerminals() &&
                extension1.getMaxActivePowerMismatch() == extension2.getMaxActivePowerMismatch() &&
                extension1.getMaxReactivePowerMismatch() == extension2.getMaxReactivePowerMismatch() &&
                extension1.getMaxVoltageMismatch() == extension2.getMaxVoltageMismatch() &&
                extension1.getMaxAngleMismatch() == extension2.getMaxAngleMismatch() &&
                extension1.getMaxRatioMismatch() == extension2.getMaxRatioMismatch() &&
                extension1.getMaxSusceptanceMismatch() == extension2.getMaxSusceptanceMismatch() &&
                extension1.getNewtonRaphsonStoppingCriteriaType() == extension2.getNewtonRaphsonStoppingCriteriaType() &&
                Objects.equals(extension1.getVoltageTargetPriorities(), extension2.getVoltageTargetPriorities()) &&
                extension1.isTransformerVoltageControlUseInitialTapPosition() == extension2.isTransformerVoltageControlUseInitialTapPosition() &&
                extension1.getGeneratorVoltageControlMinNominalVoltage() == extension2.getGeneratorVoltageControlMinNominalVoltage() &&
                extension1.getFictitiousGeneratorVoltageControlCheckMode() == extension2.getFictitiousGeneratorVoltageControlCheckMode() &&
                extension1.isAreaInterchangeControl() == extension2.isAreaInterchangeControl() &&
                Objects.equals(extension1.getAreaInterchangeControlAreaType(), extension2.getAreaInterchangeControlAreaType()) &&
                extension1.getAreaInterchangePMaxMismatch() == extension2.getAreaInterchangePMaxMismatch();
    }

    public static LoadFlowParameters clone(LoadFlowParameters parameters) {
        Objects.requireNonNull(parameters);
        LoadFlowParameters parameters2 = new LoadFlowParameters()
                .setVoltageInitMode(parameters.getVoltageInitMode())
                .setTransformerVoltageControlOn(parameters.isTransformerVoltageControlOn())
                .setUseReactiveLimits(parameters.isUseReactiveLimits())
                .setPhaseShifterRegulationOn(parameters.isPhaseShifterRegulationOn())
                .setTwtSplitShuntAdmittance(parameters.isTwtSplitShuntAdmittance())
                .setShuntCompensatorVoltageControlOn(parameters.isShuntCompensatorVoltageControlOn())
                .setReadSlackBus(parameters.isReadSlackBus())
                .setWriteSlackBus(parameters.isWriteSlackBus())
                .setDc(parameters.isDc())
                .setDistributedSlack(parameters.isDistributedSlack())
                .setBalanceType(parameters.getBalanceType())
                .setDcUseTransformerRatio(parameters.isDcUseTransformerRatio())
                .setCountriesToBalance(new HashSet<>(parameters.getCountriesToBalance()))
                .setConnectedComponentMode(parameters.getConnectedComponentMode())
                .setHvdcAcEmulation(parameters.isHvdcAcEmulation())
                .setDcPowerFactor(parameters.getDcPowerFactor());

        OpenLoadFlowParameters extension = parameters.getExtension(OpenLoadFlowParameters.class);
        if (extension != null) {
            OpenLoadFlowParameters extension2 = new OpenLoadFlowParameters()
                    .setSlackBusSelectionMode(extension.getSlackBusSelectionMode())
                    .setSlackBusesIds(new ArrayList<>(extension.getSlackBusesIds()))
                    .setSlackDistributionFailureBehavior(extension.getSlackDistributionFailureBehavior())
                    .setVoltageRemoteControl(extension.isVoltageRemoteControl())
                    .setLowImpedanceBranchMode(extension.getLowImpedanceBranchMode())
                    .setLoadPowerFactorConstant(extension.isLoadPowerFactorConstant())
                    .setPlausibleActivePowerLimit(extension.getPlausibleActivePowerLimit())
                    .setSlackBusPMaxMismatch(extension.getSlackBusPMaxMismatch())
                    .setVoltagePerReactivePowerControl(extension.isVoltagePerReactivePowerControl())
                    .setGeneratorReactivePowerRemoteControl(extension.isGeneratorReactivePowerRemoteControl())
                    .setTransformerReactivePowerControl(extension.isTransformerReactivePowerControl())
                    .setMaxNewtonRaphsonIterations(extension.getMaxNewtonRaphsonIterations())
                    .setMaxOuterLoopIterations(extension.getMaxOuterLoopIterations())
                    .setNewtonRaphsonConvEpsPerEq(extension.getNewtonRaphsonConvEpsPerEq())
                    .setVoltageInitModeOverride(extension.getVoltageInitModeOverride())
                    .setTransformerVoltageControlMode(extension.getTransformerVoltageControlMode())
                    .setShuntVoltageControlMode(extension.getShuntVoltageControlMode())
                    .setMinPlausibleTargetVoltage(extension.getMinPlausibleTargetVoltage())
                    .setMaxPlausibleTargetVoltage(extension.getMaxPlausibleTargetVoltage())
                    .setMinRealisticVoltage(extension.getMinRealisticVoltage())
                    .setMaxRealisticVoltage(extension.getMaxRealisticVoltage())
                    .setReactiveRangeCheckMode(extension.getReactiveRangeCheckMode())
                    .setLowImpedanceThreshold(extension.getLowImpedanceThreshold())
                    .setNetworkCacheEnabled(extension.isNetworkCacheEnabled())
                    .setSvcVoltageMonitoring(extension.isSvcVoltageMonitoring())
                    .setStateVectorScalingMode(extension.getStateVectorScalingMode())
                    .setMaxSlackBusCount(extension.getMaxSlackBusCount())
                    .setDebugDir(extension.getDebugDir())
                    .setIncrementalTransformerRatioTapControlOuterLoopMaxTapShift(extension.getIncrementalTransformerRatioTapControlOuterLoopMaxTapShift())
                    .setSecondaryVoltageControl(extension.isSecondaryVoltageControl())
                    .setReactiveLimitsMaxPqPvSwitch(extension.getReactiveLimitsMaxPqPvSwitch())
                    .setPhaseShifterControlMode(extension.getPhaseShifterControlMode())
                    .setAlwaysUpdateNetwork(extension.isAlwaysUpdateNetwork())
                    .setMostMeshedSlackBusSelectorMaxNominalVoltagePercentile(extension.getMostMeshedSlackBusSelectorMaxNominalVoltagePercentile())
                    .setReportedFeatures(extension.getReportedFeatures())
                    .setSlackBusCountryFilter(new HashSet<>(extension.getSlackBusCountryFilter()))
                    .setActionableSwitchesIds(new HashSet<>(extension.getActionableSwitchesIds()))
                    .setActionableTransformersIds(new HashSet<>(extension.getActionableTransformersIds()))
                    .setAsymmetrical(extension.isAsymmetrical())
                    .setMinNominalVoltageTargetVoltageCheck(extension.getMinNominalVoltageTargetVoltageCheck())
                    .setReactivePowerDispatchMode(extension.getReactivePowerDispatchMode())
                    .setOuterLoopNames(extension.getOuterLoopNames())
                    .setUseActiveLimits(extension.isUseActiveLimits())
                    .setDisableVoltageControlOfGeneratorsOutsideActivePowerLimits(extension.isDisableVoltageControlOfGeneratorsOutsideActivePowerLimits())
                    .setLineSearchStateVectorScalingMaxIteration(extension.getLineSearchStateVectorScalingMaxIteration())
                    .setLineSearchStateVectorScalingStepFold(extension.getLineSearchStateVectorScalingStepFold())
                    .setMaxVoltageChangeStateVectorScalingMaxDv(extension.getMaxVoltageChangeStateVectorScalingMaxDv())
                    .setMaxVoltageChangeStateVectorScalingMaxDphi(extension.getMaxVoltageChangeStateVectorScalingMaxDphi())
                    .setLinePerUnitMode(extension.getLinePerUnitMode())
                    .setUseLoadModel(extension.isUseLoadModel())
                    .setDcApproximationType(extension.getDcApproximationType())
                    .setAcSolverType(extension.getAcSolverType())
                    .setMaxNewtonKrylovIterations(extension.getMaxNewtonKrylovIterations())
                    .setNewtonKrylovLineSearch(extension.isNewtonKrylovLineSearch())
                    .setSimulateAutomationSystems(extension.isSimulateAutomationSystems())
                    .setWriteReferenceTerminals(extension.isWriteReferenceTerminals())
                    .setMaxActivePowerMismatch(extension.getMaxActivePowerMismatch())
                    .setMaxReactivePowerMismatch(extension.getMaxReactivePowerMismatch())
                    .setMaxVoltageMismatch(extension.getMaxVoltageMismatch())
                    .setMaxAngleMismatch(extension.getMaxAngleMismatch())
                    .setMaxRatioMismatch(extension.getMaxRatioMismatch())
                    .setMaxSusceptanceMismatch(extension.getMaxSusceptanceMismatch())
                    .setNewtonRaphsonStoppingCriteriaType(extension.getNewtonRaphsonStoppingCriteriaType())
                    .setReferenceBusSelectionMode(extension.getReferenceBusSelectionMode())
                    .setVoltageTargetPriorities(extension.getVoltageTargetPriorities())
                    .setTransformerVoltageControlUseInitialTapPosition(extension.isTransformerVoltageControlUseInitialTapPosition())
                    .setGeneratorVoltageControlMinNominalVoltage(extension.getGeneratorVoltageControlMinNominalVoltage())
                    .setFictitiousGeneratorVoltageControlCheckMode(extension.getFictitiousGeneratorVoltageControlCheckMode())
                    .setAreaInterchangeControl(extension.isAreaInterchangeControl())
                    .setAreaInterchangeControlAreaType(extension.getAreaInterchangeControlAreaType())
                    .setAreaInterchangePMaxMismatch(extension.getAreaInterchangePMaxMismatch());

            if (extension2 != null) {
                parameters2.addExtension(OpenLoadFlowParameters.class, extension2);
            }
        }

        return parameters2;
    }
}

