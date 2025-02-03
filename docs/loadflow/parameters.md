# Configuration

To use PowSyBl Open Load Flow for all power flow computations, you have to configure the `load-flow` module in your configuration file:
```yaml
load-flow:
  default-impl-name: "OpenLoadFlow"
```

## Generic parameters

Generic parameters are documented in [PowSyBl Core](inv:powsyblcore:*:*#simulation/loadflow/configuration).

The next section details the parameters that are specific to PowSyBl Open LoadFLow.

## Specific parameters

**voltageInitModeOverride**  
Additional voltage init modes of PowSyBl Open Load Flow that are not present in [PowSyBl LoadFlow `voltageInitMode` Parameter](inv:powsyblcore:*:*#simulation/loadflow/configuration):
- `NONE`: no override
- `VOLTAGE_MAGNITUDE`: specific initializer to initialize voltages magnitudes $v$, leaving $\theta=0$. Proven useful for
  unusual input data with transformers rated voltages very far away from bus nominal voltages.
- `FULL_VOLTAGE`: voltages magnitudes $v$ initialized using `VOLTAGE_MAGNITUDE` initializer, $\theta$ initialized using a DC load flow.

The default value is `NONE`.

**lowImpedanceBranchMode**  
The `lowImpedanceBranchMode` property is an optional property that defines how to deal with low impedance branches
(when $Z$ is less than the per-unit `lowImpedanceThreshold`, see further below).
Possible values are:
- Use `REPLACE_BY_ZERO_IMPEDANCE_LINE` if you want to consider low impedance branches as zero impedance branches.
- Use `REPLACE_BY_MIN_IMPEDANCE_LINE` if you want to consider low impedance branches with a value equal to the `lowImpedanceThreshold`.

The default value is `REPLACE_BY_ZERO_IMPEDANCE_LINE`.

**lowImpedanceThreshold**  
The `lowImpedanceThreshold` property is an optional property that defines in per-unit the threshold used to identify low impedance branches
(when $Z$ is less than the `lowImpedanceThreshold` per-unit threshold).  
The default value is $10^{-8}$ and it must be greater than `0`.

**slackDistributionFailureBehavior**  
This option defines the behavior in case the slack distribution fails. Available options are:
- `THROW` if you want an exception to be thrown in case of failure
- `FAIL` if you want the OuterLoopStatus to be `FAILED` in case of failure
- `LEAVE_ON_SLACK_BUS` if you want to leave the remaining slack on the slack bus
- `DISTRIBUTE_ON_REFERENCE_GENERATOR` if you want to put the slack on the reference generator, disregarding active power limits.
  There must be a reference generator defined, i.e. `referenceBusSelectionMode` must be `GENERATOR_REFERENCE_PRIORITY` - otherwise this mode falls back to `FAIL` mode automatically.

The default value is `LEAVE_ON_SLACK_BUS`.

**slackBusSelectionMode**  
The `slackBusSelectionMode` property is an optional property that defines how to select the slack bus. The three options are available through the configuration file:
- `FIRST` if you want to choose the first bus of all the network buses.
- `NAME` if you want to choose a specific bus as the slack bus. In that case, the `slackBusesIds` property has to be filled.
- `MOST_MESHED` if you want to choose the most meshed bus among buses with the highest nominal voltage as the slack bus.
  This option is typically required for computation with several synchronous components.
- `LARGEST_GENERATOR` if you want to choose the bus with the highest total generation capacity as the slack bus.

The default value is `MOST_MESHED`.

Note that if you want to choose the slack buses that are defined inside the network with
a [slack terminal extension](inv:powsyblcore:*:*:#slack-terminal-extension),
you have to set the [PowSyBl LoadFlow `readSlackBus` Parameter](inv:powsyblcore:*:*#simulation/loadflow/configuration) to `true`.
When `readSlackBus` is set to true, `slackBusSelectionMode` is still used and serves as a secondary selection criteria:
- for e.g. synchronous components where no slack terminal extension is present.
- for e.g. synchronous components where more than `maxSlackBusCount` slack terminal extensions are present.

**mostMeshedSlackBusSelectorMaxNominalVoltagePercentile**  
This option is used when `slackBusSelectionMode` is set to `MOST_MESHED`. It sets the maximum nominal voltage percentile.
The default value is `95` and it must be inside the interval [`0`, `100`].

**maxSlackBusCount**  
Number of slack buses to be selected. Setting a value above 1 can help convergence on very large networks with large initial imbalances,
where it might be difficult to find a single slack with sufficient branches connected and able to absorb or evacuate the slack power.  
The default value is `1`.

**slackBusesIds**  
The `slackBusesIds` property is a required property if you choose `NAME` for property `slackBusSelectionMode`.
It defines a prioritized list of buses or voltage levels to be chosen for slack bus selection (as an array, or as a comma or semicolon separated string).

**slackBusCountryFilter**  
The `slackBusCountryFilter` defines a list of countries where slack bus should be selected (as an array, or as a comma or semicolon separated string).  
Countries are specified by their alpha 2 code (e.g. `FR`, `BE`, `DE`, ...).  
The default value is an empty list (any country can be used for slack bus selection).

**loadPowerFactorConstant**  
The `loadPowerFactorConstant ` property is an optional boolean property. This property is used in the outer loop that distributes slack on loads if :
- `distributedSlack` property is set to true in the [load flow default parameters](inv:powsyblcore:*:*#simulation/loadflow/configuration),
- `balanceType` property is set to `PROPORTIONAL_TO_LOAD` or `PROPORTIONAL_TO_CONFORM_LOAD` in the [load flow default parameters](inv:powsyblcore:*:*#simulation/loadflow/configuration).

The default value is `false`.

If prerequisites fulfilled and `loadPowerFactorConstant` property is set to `true`, the distributed slack outer loop adjusts the load P value and adjusts also the load Q value in order to maintain the power factor as a constant value.
At the end of the load flow calculation, $P$ and $Q$ at loads terminals are both updated. Note that the power factor of a load is given by this equation :

$$
Power Factor = {\frac {P} {\sqrt {P^2+{Q^2}}}}
$$

Maintaining the power factor constant from an updated active power $P^‎\prime$ means we have to isolate $Q^‎\prime$ in this equation :

$$
{\frac {P} {\sqrt {P^2+{Q^2}}}}={\frac {P^‎\prime} {\sqrt {P^‎\prime^2+{Q^‎\prime^2}}}}
$$

Finally, a simple rule of three is implemented in the outer loop :

$$
Q^\prime={\frac {Q P^\prime} {P}}
$$

If `balanceType` equals to `PROPORTIONAL_TO_LOAD`, the power factor remains constant scaling the global $P0$ and $Q0$ of the load.
If `balanceType` equals to `PROPORTIONAL_TO_CONFORM_LOAD`, the power factor remains constant scaling only the variable parts. Thus, we fully rely on [load detail extension](inv:powsyblcore:*:*:#load-detail-extension).

In both cases, slack is not distributed to fictitious loads. A load can be fictitious by setting its boolean attribute `isFictitious` or by having a `loadType` equal to `LoadType.FICTITIOUS`.

The default value for `loadPowerFactorConstant` property is `false`.

**slackBusPMaxMismatch**  
When slack distribution is enabled (`distributedSlack` set to `true` in LoadFlowParameters), this is the threshold below which slack power
is considered to be distributed.  
The default value is `1 MW` and it must be greater or equal to `0 MW`.

**areaInterchangeControl**  
The `areaInterchangeControl` property is an optional property that defines if the [area interchange control](loadflow.md#area-interchange-control) outer loop is enabled.
If set to `true`, the area interchange control outer loop will be used instead of the slack distribution outer loop.  
The default value is `false`.

**areaInterchangeControlAreaType**  
Defines the `areaType` of the areas on which the [area interchange control](loadflow.md#area-interchange-control) is applied.
Only the areas of the input network that have this type will be considered.  
The default value is `ControlArea`.

**areaInterchangePMaxMismatch**  
Defines the maximum interchange mismatch tolerance for [area interchange control](loadflow.md#area-interchange-control).
The default value is `2 MW` and it must be greater than `0 MW`.

**voltageRemoteControl**  
The `voltageRemoteControl` property is an optional property that defines if the remote control for voltage controllers has to be modeled.
If set to false, any existing voltage remote control is converted to a local control, rescaling the target voltage
according to the nominal voltage ratio between the remote regulated bus and the equipment terminal bus.  
The default value is `true`.

**voltagePerReactivePowerControl**  
Whether simulation of static VAR compensators with voltage control enabled and a slope defined should be enabled
(See [voltage per reactive power control extension](inv:powsyblcore:*:*:#voltage-per-reactive-power-control-extension)).  
The default value is `false`.

**generatorReactivePowerRemoteControl**  
Whether simulation of generators reactive power remote control should be enabled
(See [remote reactive power control](inv:powsyblcore:*:*:#remote-reactive-power-control-extension)).  
The default value is `false`.

**secondaryVoltageControl**  
Whether simulation of secondary voltage control should be enabled.  
Modeling of secondary voltage control has been designed to provide a fast, static, approximation of the equilibrium state of the generator reactive power 
alignment process that controls the voltage of a remote pilot point.
This reactive power alignment process typically takes several minutes on the network.
The default value is `false`.

Please note that the secondaryVoltageControl implementation has the folowing limitation:  
Generators that belongs to a secondary voltage control zone should be in local voltage control only.
If secondaryVoltageControl is set to `true`, generators that belongs to a secondary voltage control zone and that are configured 
for remote voltage control are switched to local voltage control with an initial local target equals to remoteTarget / remoteNominalV * localNominalV . 

**reactiveLimitsMaxPqPvSwitch**  
When `useReactiveLimits` is set to `true`, this parameter is used to limit the number of times an equipment performing voltage control
is switching from PQ to PV type. After this number of PQ/PV type switch, the equipment will not change PV/PQ type anymore.  
The default value is `3` and it must be greater or equal to `0`.

**phaseShifterControlMode**  
- `CONTINUOUS_WITH_DISCRETISATION`: phase shifter control is solved by the Newton-Raphson inner-loop.
- `INCREMENTAL`: phase shifter control is solved in the outer-loop

The default value is `CONTINUOUS_WITH_DISCRETISATION`.

**transformerVoltageControlMode**  
This parameter defines which kind of outer loops is used for transformer voltage controls. We have three kinds of outer loops:
- `WITH_GENERATOR_VOLTAGE_CONTROL` means that a continuous voltage control is performed in the same time as the generator voltage control. The final transformer $\rho$ is obtained by rounding to the closest tap position. The control deadband is not taken into account.
- `AFTER_GENERATOR_VOLTAGE_CONTROL` means that a continuous voltage control is performed after the generator voltage control. The final transformer $\rho$ is obtained by rounding to the closest tap position. The control deadband is taken into account.
- `INCREMENTAL_VOLTAGE_CONTROL` means that an incremental voltage control is used. $\rho$ always corresponds to a tap position. Tap changes using sensitivity computations. The control deadband is taken into account.

The default value is `WITH_GENERATOR_VOLTAGE_CONTROL`.

**transformerReactivePowerControl**  
This parameter enables the reactive power control of transformer through a dedicated incremental reactive power control outer loop. The default value is `false`.

**incrementalTransformerRatioTapControlOuterLoopMaxTapShift**  
Maximum number of tap position change during a single iteration of the incremental voltage and or reactive power control outer loop.
Applies when `transformerVoltageControlMode` is set to `INCREMENTAL_VOLTAGE_CONTROL` and or when `transformerReactivePowerControl` is enabled (`true`). The default value is `3`.

**shuntVoltageControlMode**  
This parameter defines which kind of outer loops is used for the shunt voltage control. We have two kinds of outer loops:
- `WITH_GENERATOR_VOLTAGE_CONTROL` means that a continuous voltage control is performed in the same time as the generator voltage control. Susceptance is finally rounded to the closest section for shunt that are controlling voltage. The control deadband is not taken into account.
- `INCREMENTAL_VOLTAGE_CONTROL` means that an incremental voltage control is used. Susceptance always corresponds to a section. Section changes using sensitivity computations. The control deadband is taken into account.

The default value is `WITH_GENERATOR_VOLTAGE_CONTROL`.

**svcVoltageMonitoring**  
Whether simulation of static VAR compensators voltage monitoring should be enabled.  
The default value is `true`.

**acSolverType**  
AC load flow solver engine. Currently, it can be one of:
- `NEWTON_RAPHSON` is the standard Newton-Raphson algorithm for load flow. Solves linear systems via Sparse LU decomposition (by [SuiteSparse](https://people.engr.tamu.edu/davis/suitesparse.html));
- `NEWTON_KRYLOV` is also the standard Newton-Raphson algorithm for load flow. Solves linear systems via Krylov subspace methods for indefinite non-symmetric matrices (by [Kinsol](https://computing.llnl.gov/projects/sundials/kinsol)).

The default value is `NEWTON_RAPHSON`.

**maxOuterLoopIterations**  
Maximum number of iterations for Newton-Raphson outer loop.  
The default value is `20` and it must be greater or equal to `1`.

**newtonRaphsonStoppingCriteriaType**  
Stopping criteria for Newton-Raphson algorithm.
- `UNIFORM_CRITERIA`: stop when quadratic norm of all mismatches vector is below quadratic norm of mismatches of value `newtonRaphsonConvEpsPerEq`. This criteria is defined by the following formula (for $n$ equations):

$$\sqrt {mismatch_1^2 + mismatch_2^2 + ... + mismatch_n^2} < \sqrt{n * newtonRaphsonConvEpsPerEq^2}$$

  `newtonRaphsonConvEpsPerEq` defines the corresponding threshold for all equation types, in per-unit. The default value is $10^{-4} \text{p.u.}$ and must be greater than 0.
- `PER_EQUATION_TYPE_CRITERIA`: stop when equation mismatches are below equation type specific thresholds:
    - `maxActivePowerMismatch`: Defines the threshold for active power equations, in MW. The default value is $10^{-2} \text{MW}$ and it must be greater than 0.
    - `maxReactivePowerMismatch`: Defines the threshold for reactive power equations, in MVAr. The default value is $10^{-2} \text{MVAr}$ and it must be greater than 0.
    - `maxVoltageMismatch`: Defines the threshold for voltage equations, in per-unit. The default value is $10^{-4} \text{p.u.}$ and it must be greater than 0.
    - `maxAngleMismatch`: Defines the threshold for angle equations, in radians. The default value is $10^{-5} \text{rad}$ and it must be greater than 0.
    - `maxRatioMismatch`: Defines the threshold for ratio equations, unitless. The default value is $10^{-5}$ and it must be greater than 0.
    - `maxSusceptanceMismatch`: Defines the threshold for susceptance equations, in per-unit. The default value is $10^{-4} \text{p.u.}$ and it must be greater than 0.

The default value is `UNIFORM_CRITERIA`.

**maxNewtonRaphsonIterations**  
Only applies if **acSolverType** is `NEWTON_RAPHSON`.
Maximum number of iterations for Newton-Raphson inner loop.  
The default value is `15` and it must be greater or equal to `1`.

**maxNewtonKrylovIterations**  
Only applies if **acSolverType** is `NEWTON_KRYLOV`.
Maximum number of iterations for Newton-Raphson inner loop.
The default value is `100` and it must be greater or equal to `1`.

**stateVectorScalingMode**  
Only applies if **acSolverType** is `NEWTON_RAPHSON`.
This parameter 'slows down' the Newton-Raphson by scaling the state vector between iterations. Can help convergence in some cases.
- `NONE`: no scaling is made
- `LINE_SEARCH`: applies a line search strategy
- `MAX_VOLTAGE_CHANGE`: scale by limiting voltage updates to a maximum amplitude p.u. and a maximum angle.

The default value is `NONE`.

**lineSearchStateVectorScalingMaxIteration**  
Only applies if **acSolverType** is `NEWTON_RAPHSON` and if **stateVectorScalingMode** is `LINE_SEARCH`.  
Maximum iterations for a vector scaling when applying a line search strategy.  
The default value is `10` and it must be greater or equal to `1`.

**lineSearchStateVectorScalingStepFold**  
Only applies if **acSolverType** is `NEWTON_RAPHSON` and if **stateVectorScalingMode** is `LINE_SEARCH`.  
At the iteration $i$ of vector scaling with the line search strategy, with this parameter having the value $s$ , the step size will be $ \mu  = \frac{1}{s^i}$ .   
The default value is `4/3 = 1.333` and it must be greater than `1`.

**maxVoltageChangeStateVectorScalingMaxDv**  
Only applies if **acSolverType** is `NEWTON_RAPHSON` and if **stateVectorScalingMode** is `MAX_VOLTAGE_CHANGE`.  
Maximum amplitude p.u. for a voltage change.  
The default value is `0.1 p.u.` and it must be greater than `0`.

**maxVoltageChangeStateVectorScalingMaxDphi**  
Only applies if **acSolverType** is `NEWTON_RAPHSON` and if **stateVectorScalingMode** is `MAX_VOLTAGE_CHANGE`.  
Maximum angle for a voltage change.  
The default value is `0.174533 radians` (`10°`) and it must be greater than `0`.

**newtonKrylovLineSearch**  
Only applies if **acSolverType** is `NEWTON_KRYLOV`.
Activates or deactivates line search for the Newton-Raphson Kinsol solver.
The default value is `false`.

**plausibleActivePowerLimit**  
The `plausibleActivePowerLimit` property is an optional property that defines a maximal active power limit for generators to be considered as participating elements for:
- slack distribution (if `balanceType` equals to any of the `PROPORTIONAL_TO_GENERATION_<any>` types)
- slack selection (if `slackBusSelectionMode` equals to `LARGEST_GENERATOR`)

The default value is $5000 MW$.

**minPlausibleTargetVoltage** and **maxPlausibleTargetVoltage**  
Equipments with voltage regulation target voltage outside these per-unit thresholds
are considered suspect and are discarded from regulation prior to load flow resolution.  
The default values are `0.8` and `1.2` and they must be greater or equal to `0`.

**minRealisticVoltage** and **maxRealisticVoltage**  
These parameters are used to identify if Newton-Raphson has converged to an unrealistic state.
For any component where a bus voltage is solved outside these per-unit thresholds, the component solution is deemed unrealistic
and its solution status is flagged as failed.  
The default values are `0.5` and `1.5` and they must be greater or equal to `0`.

**reactiveRangeCheckMode**  
Open Load Flow discards voltage control for generators with a too small reactive power range, because in practice a too
small reactive power ranger means limited to zero voltage control capability.

For a given active power output, the reactive power range is defined as $MaxQ - MinQ$ (always a positive value).  
The *maximum* and *minimum* reactive range of a generator is:
- for generators without reactive limits: infinity 
- for generators with reactive limits defined by a pair of [min/max values](inv:powsyblcore:*:*:#min-max-reactive-limits), both minimum and maximum reactive range are equal to $MaxQ - MinQ$ 
- for generators with reactive limits defined by a [reactive capability curve](inv:powsyblcore:*:*:#reactive-capability-curve), the minimum (resp. maximum) reactive range is obtained by finding the curve point having the minimum (resp. maximum) $MaxQ - MinQ$.

The `reactiveRangeCheckMode` parameter defines how generator reactive power range is to be tested. If the test does not pass,
meaning the reactive power range is too small, then the voltage control is disabled:
- `MIN_MAX` mode tests if the minimum reactive range is not `0 MVAr` and if the maximum reactive range is above `1 MVAr`.
- `MAX` mode tests if the maximum reactive range is above `1 MVAr`.
- `TARGET_P` tests if the reactive range at $TargetP$ is above `1 MVAr`.

The default value is `MAX`.

**reportedFeatures**  
This parameter allows to define a set of features which should generate additional reports (as an array, or as a comma or semicolon separated string).
In current version this parameter can be used to request Newton-Raphson iterations report:
- `NEWTON_RAPHSON_LOAD_FLOW`: report Newton-Raphson iteration log for load flow calculations.
- `NEWTON_RAPHSON_SECURITY_ANALYSIS`: report Newton-Raphson iteration log for security analysis calculations.
- `NEWTON_RAPHSON_SENSITIVITY_ANALYSIS`: report Newton-Raphson iteration log for sensitivity analysis calculations.

Newton-Raphson iterations report consist in reporting:
- the involved synchronous component
- the involved Newton-Raphson outer loop iteration
- for each Newton-Raphson inner loop iteration:
    - maximum active power mismatch, the related bus Id with current solved voltage magnitude and angle.
    - maximum reactive power mismatch, the related bus Id with current solved voltage magnitude and angle.
    - maximum voltage control mismatch, the related bus Id with current solved voltage magnitude and angle.
    - the norm of the mismatch vector

The default value is an empty set of features to report.

**networkCacheEnabled**  
This parameter is used to run fast simulations by applying incremental modifications on the network directly to the Open Load Flow internal modeling.
The cache mode allows faster runs when modifications on the network are light.
Not all modifications types are supported yet, currently supported modifications are:
- target voltage modification
- switch open/close status modification. The switches to be modified must be configured via the `actionableSwitchesIds` property (as an array, or as a comma or semicolon separated string).

The default value is `false`.

**actionableSwitchesIds**  
This parameter list is used if `networkCachedEnabled` is activated. It defines a list of switches that might be modified (as an array, or as a comma or semicolon separated string). When one of the switches changes its status (open/close) and a load flow is run just after, the cache will be used to a faster resolution. Note that in the implementation, all the switches of that list will be considered as retained, leading to a size increase of the Jacobian matrix. The list should have a reasonable size, otherwise the simulation without cache use should be preferred.

**alwaysUpdateNetwork**  
Update the iIDM network state even in case of non-convergence.  
The default value is `false`.

**debugDir**  
Allows to dump debug files to a specific directory.  
The default value is undefined (`null`), disabling any debug files writing.

**asymmetrical**  
Allows to run asymmetrical calculations. The default value is `false`.

**useActiveLimits**  
Allows to ignore active power limits during calculations. Active power limits are mainly involved in slack distribution on generators. The default value is `true`.

**disableVoltageControlOfGeneratorsOutsideActivePowerLimits**  
Disables voltage control for generators with `targetP` outside the interval [`minP`, `maxP`]. The default value is `false`.

**minNominalVoltageTargetVoltageCheck**  
This parameter defines the minimal nominal voltage to check the target of voltage control in per-unit.
The default value is `20 kV`, meaning that under the controlled buses of voltage levels under this value are ignored from the check.
It must be greater or equal to `0 kV`.

**reactivePowerDispatchMode**  
This parameter defines how reactive power is split among generators with controls (voltage or reactive power).
It tries to divide reactive power among generators in the order described below.
`reactivePowerDispatchMode` can be one of:
- `Q_EQUAL_PROPORTION`
    1. If all concerned generators have pre-defined reactive keys via the [Coordinated Reactive Control extension](inv:powsyblcore:*:*:#coordinated-reactive-control-extension), then it splits `Q` proportional to reactive keys
    2. If they don't, but they have plausible reactive limits, split proportionally to the maximum reactive power range
    3. If they don't, split `Q` equally
- `K_EQUAL_PROPORTION`
    1. If generators have plausible reactive limits, split `Q` proportionally to `k`, where `k` is defined by
       $ k = \frac{2 qToDispatch - qmax1 - qmin1 - qmax2 - qmin2 - ...}{qmax1 - qmin1 + qmax2 - qmin2 + ...} $
    2. If they don't, split `Q` equally

The default value is `Q_EQUAL_PROPORTION`.

**disableVoltageControlOfGeneratorsOutsideActivePowerLimits**  
This parameter allows to disable the voltage control of generators which `targetP` is lower than `minP` or greater than `maxP`. The default value is `false`.

**outerLoopNames**  

> **Note**: This is an advanced parameter.
> Unless you have specific needs, use the default outer loop order built-in PowSyBl Open Load Flow.

This parameter allows to configure both the list of outer loops that can be executed and their explicit execution order.
Each outer loop name specified in the list must be unique and match the `NAME` attribute of the respective outer loop.

By default, this parameter is set to `null`, and the activated outer loops are executed in a default order
(as defined in `DefaultAcOuterLoopConfig` for AC Load Flow and `DefaultDcOuterLoopConfig` for DC Load Flow).

Here an example with slack distribution and reactive limits consideration, in AC Load Flow, when `outerLoopNames` is **not** used:
- the creation of the `DistributedSlack` *outerloop* is driven by the parameter `distributedSlack` *parameter*.
- the creation of the `ReactiveLimits` *outerloop* is driven by the parameter `useReactiveLimits` *parameter*.
- PowSyBl Open Load Flow default order is to run the `DistributedSlack` outerloop first, and then the `ReactiveLimits` outerloop.

Continuing this example, the outer loops creation and execution order can be modified by setting `outerLoopNames` to
value `['ReactiveLimits', 'DistributedSlack']`, in this case PowSyBl Open Load Flow will the `ReactiveLimits` outerloop first,
and then the `DistributedSlack` outerloop.

For AC load flow the supported outer loop names, their default execution order, and their corresponding high-level parameter, are:
1. `DistributedSlack` / `AreaInterchangeControl` (parameters: `distributedSlack` / `areaInterchangeControl`)
2. `SecondaryVoltageControl` (parameter: `secondaryVoltageControl`)
3. `VoltageMonitoring` (parameter: `svcVoltageMonitoring`)
4. `ReactiveLimits` (parameter: `useReactiveLimits`)
5. `PhaseControl` / `IncrementalPhaseControl` (parameters: `phaseShifterRegulationOn` and `phaseShifterControlMode`)
6. `SimpleTransformerVoltageControl` / `TransformerVoltageControl` / `IncrementalTransformerVoltageControl` (parameters: `transformerVoltageControlOn` and `transformerVoltageControlMode`)
7. `IncrementalTransformerReactivePowerControl` (parameter: `transformerReactivePowerControl`)
8. `ShuntVoltageControl` / `IncrementalShuntVoltageControl` (parameters: `shuntVoltageControl` and `shuntVoltageControlMode`)
9. `AutomationSystem` (parameter: `simulateAutomationSystems`)

And for DC load flow:
1. `IncrementalPhaseControl` (parameter: `phaseShifterRegulationOn`)
2. `AreaInterchangeControl` (parameter: `areaInterchangeControl`)

**linePerUnitMode**  
This parameter defines how lines ending in different nominal voltages at both sides are perunit-ed.
`linePerUnitMode` can be one of:
- `IMPEDANCE`: handle difference in nominal voltage via impedance correction
- `RATIO`: handle difference in nominal voltage by introducing a ratio

The default value is `IMPEDANCE`.

**useLoadModel**  
When set to `true`, this parameter enables the modeling of the `ZIP` or `EXPONENTIAL` response characteristic of a [Load](inv:powsyblcore:*:*:#load).

**dcApproximationType**  
This parameter defines how resistance is neglected compared to inductance in DC approximation.
`dcApproximationType` can be one of:
- `IGNORE_R`: consider that r << x
- `IGNORE_G`: consider that g << b

The default value is `IGNORE_R`.

**simulateAutomationSystems**  
Allows to simulate automation systems that are modeled in the network. For the moment, the grid model only supports overload management systems. The default behaviour is `false`.

**referenceBusSelectionMode**  
The reference bus is the bus where the angle is equal to zero. There are several mode of selection:
- `FIRST_SLACK`: the angle reference bus is selected as the first slack bus among potentially multiple slacks (in case `maxSlackBusCount` > 1).
- `GENERATOR_REFERENCE_PRIORITY`: the angle reference bus is selected from generator reference priorities defined via the [Reference Priority extension](inv:powsyblcore:*:*:#reference-priority-extension).

The default value is `FIRST_SLACK`.

**writeReferenceTerminals**  
This parameter allows to write to the IIDM network the [Reference Terminals extension](inv:powsyblcore:*:*:#reference-terminals-extension)
containing the generator terminals used as angle reference in the load flow calculation.
There is one Terminal created/added in the extension for each calculated Synchronous Component.
Works only when `referenceBusSelectionMode` is set to `GENERATOR_REFERENCE_PRIORITY`.

**voltageTargetPriorities**  
When multiple equipment regulate the same bus with different voltage targets,
this parameter enables configuring priority to resolve inconsistencies by aligning the voltage targets.
Priority is determined by equipment type order; the voltage target of the equipment type listed first takes precedence over those listed later.
By default, the order is `["GENERATOR", "TRANSFORMER", "SHUNT"]`.  
Note that `"GENERATOR"` indistinctively includes generators, batteries, static var compensators, and VSC HVDC converters.

If the user specifies only a sub-list of priorities, this sub-list is completed by the
order defined by default. Thus, if the user specifies only `["TRANSFORMER"]`,
it will be completed to `["TRANSFORMER", "GENERATOR", "SHUNT"]`.

**transformerVoltageControlUseInitialTapPosition**  
This parameter is only used if the transformer voltage control is enabled and of mode `AFTER_GENERATOR_VOLTAGE_CONTROL`.
If this parameter is set to `true`, transformers of the same voltage control (meaning controlling the same bus) are merged
and the algorithm tries to maintain the initial difference between rhos. If the voltage at controlled bus is closed to the
target voltage at initial step of the outer loop, the algorithm blocks transformers at their initial tap positions.
The default value of this parameter is `false`.

**generatorVoltageControlMinNominalVoltage**
This parameter is only used if the transformer voltage control is enabled and of mode `AFTER_GENERATOR_VOLTAGE_CONTROL`.
In this mode, at the first step of the outer loop, the transformers that controlled voltage are disabled, only generators
are participating to voltage control. At second step, generators controlling voltage at a controlled bus above
`generatorVoltageControlMinNominalVoltage` have their voltage control disabled. This parameter overrides an automatic
detection of the minimal nominal voltage based on an analysis of nominal voltages controlled by transformers. The default
value of this parameter is $-1$: with this value the parameter is ignored and the outer loop relies only on the automatic
detection.

**fictitiousGeneratorVoltageControlCheckMode**  
Specifies the active power checks exemption for fictitious generators voltage control.

PowSyBl open-loadflow performs these checks on generators:
- if `targetP` equals zero, voltage control is disabled.
- if parameter `disableVoltageControlOfGeneratorsOutsideActivePowerLimits` is enabled, a generator with a `targetP` lower
than `minP` or greater than `maxP`, voltage control is disabled.

The `fictitiousGeneratorVoltageControlCheckMode` option controls whether the above checks must be performed for fictitious generators: 
- use mode `FORCED` for an exemption of the two previous checks for fictitious generators.
- Use mode `NORMAL` for no exemption at all, i.e. fictitious generators are processed identically to real generators.
 
The default mode is `FORCED`.


## Configuration file example
See below an extract of a config file that could help:

```yaml
open-loadflow-default-parameters:
  lowImpedanceBranchMode: REPLACE_BY_ZERO_IMPEDANCE_LINE
  slackDistributionFailureBehavior: LEAVE_ON_SLACK_BUS
  voltageRemoteControl: false
  slackBusSelectionMode: NAME
  slackBusesIds: Bus3_0,Bus5_0
  loadPowerFactorConstant: true
```

At the moment, overriding the parameters by a JSON file is not supported by Open Load Flow.
