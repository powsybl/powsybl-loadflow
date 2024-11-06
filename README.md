# PowSyBl Open Load Flow

[![Actions Status](https://github.com/powsybl/powsybl-open-loadflow/workflows/CI/badge.svg)](https://github.com/powsybl/powsybl-open-loadflow/actions)
[![Coverage Status](https://sonarcloud.io/api/project_badges/measure?project=com.powsybl%3Apowsybl-open-loadflow&metric=coverage)](https://sonarcloud.io/component_measures?id=com.powsybl%3Apowsybl-open-loadflow&metric=coverage)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=com.powsybl%3Apowsybl-open-loadflow&metric=alert_status)](https://sonarcloud.io/dashboard?id=com.powsybl%3Apowsybl-open-loadflow)
[![MPL-2.0 License](https://img.shields.io/badge/license-MPL_2.0-blue.svg)](https://www.mozilla.org/en-US/MPL/2.0/)
[![Slack](https://img.shields.io/badge/slack-powsybl-blueviolet.svg?logo=slack)](https://join.slack.com/t/powsybl/shared_invite/zt-rzvbuzjk-nxi0boim1RKPS5PjieI0rA)

PowSyBl (**Pow**er **Sy**stem **Bl**ocks) is an open source library written in Java, that makes it easy to write complex
software for power systems’ simulations and analysis. Its modular approach allows developers to extend or customize its
features.

PowSyBl is part of the LF Energy Foundation, a project of The Linux Foundation that supports open source innovation projects
within the energy and electricity sectors.

<p align="center">
<img src="https://raw.githubusercontent.com/powsybl/powsybl-gse/main/gse-spi/src/main/resources/images/logo_lfe_powsybl.svg?sanitize=true" alt="PowSyBl Logo" width="50%"/>
</p>

Read more at https://www.powsybl.org !

This project and everyone participating in it is under the [Linux Foundation Energy governance principles](https://www.powsybl.org/pages/project/governance.html) and must respect the [PowSyBl Code of Conduct](https://github.com/powsybl/.github/blob/main/CODE_OF_CONDUCT.md).
By participating, you are expected to uphold this code. Please report unacceptable behavior to [powsybl-tsc@lists.lfenergy.org](mailto:powsybl-tsc@lists.lfenergy.org).

## PowSyBl vs PowSyBl Open Load Flow

PowSyBl Open Load Flow provides:
- An open-source implementation of the [LoadFlow API from PowSyBl Core](https://powsybl.readthedocs.io/projects/powsybl-core/en/latest/simulation/loadflow/index.html), supporting DC and AC calculations.
- An open-source implementation of the [SecurityAnalysis API from PowSyBl Core](https://powsybl.readthedocs.io/projects/powsybl-core/en/latest/simulation/security/index.html), supporting DC and AC calculations.
- An open-source implementation of the [SensitivityAnalysis API from PowSyBl Core](https://powsybl.readthedocs.io/projects/powsybl-core/en/latest/simulation/sensitivity/index.html), supporting DC and AC calculations.

Most of the code is written in Java. It only relies on native code for the [KLU](http://faculty.cse.tamu.edu/davis/suitesparse.html) sparse linear solver. Linux, Windows and MacOS are supported. KLU is distributed with license LGPL-2.1+.

Let's visit our work-in-progress [functional documentation](https://powsybl.readthedocs.io/projects/powsybl-open-loadflow/en/latest/loadflow/parameters.html)! 

### Common features

The AC calculations are based on full Newton-Raphson algorithm. The DC calculations are based on direct current linear approximation. Open Load Flow relies on:
 - Fast and robust convergence, based on [KLU](http://faculty.cse.tamu.edu/davis/suitesparse.html) sparse solver.
 - Distributed slack (on generators, on loads, or on conform loads); Manual or automatic slack bus selection as explained [here](https://powsybl.readthedocs.io/projects/powsybl-open-loadflow/en/latest/loadflow/parameters.html).
 - Support of generators' active and reactive power limits, including the support of reactive capability curves.
 - 5 voltage initialization modes: flat, warm, angles-only based on a DC load flow, magnitude-only initialization based on a specific initializer, or both voltages angle and magnitude initialization based on the two previous methods.
 - Support of zero impedance branches, including complex zero impedance subnetworks, particularly important in case of voltage controls and topology changes involved in contingencies or in remedial actions.
 - Multiple synchronous component calculation, generally linked to HVDC lines.
 - Modeling of secondary voltage control following research of [Balthazar Donon, Liège University](https://www.montefiore.uliege.be/cms/c_3482915/en/montefiore-directory?uid=u239564).
 - Support of asymmetrical calculations.
 - Implementation of three methods to update the state vector in the Newton-Raphson algorithm: classic, rescaling under maximum voltage change and linear search rescaling.

 ### About controls

 Open Load Flow supports:
 - Generator and static var compensator voltage remote control through PQV bus modelling. It supports any kind of shared voltage control between controllers that can be generators, static var compensators, or VSC converter stations.
 - Static var compensator local voltage control with a slope (support the powsybl-core extension [```VoltagePerReactivePowerControl```](https://powsybl.readthedocs.io/projects/powsybl-core/en/latest/grid_model/extensions.html#voltage-per-reactive-power-control).
 - Local and remote phase control: phase tap changers can regulate active power flows or limit currents at given terminals.
 - Local and remote voltage control by transformers, including shared controls.
 - Local and remote voltage control by shunts, including shared controls.
 - Remote reactive power control of a branch by generators, including shared controls.
 - Remote reactive power control of a branch by transformers.

Heterogeneous voltage controls management has become a key feature. All well-modeled voltage controls are kept and managed through a priority and a complex management of zero impedance lines. The generators have the first priority, followed by transformers, and then shunts. In a load flow run, in a controlled bus, only the main voltage control of highest priority controls voltage. When incremental outer loops are used, secondary priorities voltage controls can help generators that have reached reactive limits.

### Security analysis implementation 

 - Network in node/breaker topology and in bus/breaker topology.
 - Support of all types of contingency. Note that in case of a shunt compensator contingency, we don't support a contingency on it with a global voltage control by shunts at this stage. Bus contingency and bus bar section contingency are supported, leading in many case to branches opened at one side. 
 - All kind of operational limits violations detection on branches (permanent and temporary limits): current limits, apparent power limits, active power limits.
 - High and low voltage limits violations detection on buses.
 - Voltage angle limits violation.
 - Complex cases where the contingency leads to another synchronous component where a new resolution has to be performed are not supported at that stage. The loss of slack bus during a contingency is not supported yet, but the work is in progress.
 - The active and reactive power flows on branches, as well as angle and voltage at buses, can be monitored and collected for later analysis after the base case and after each contingency.
 - Remedial actions such as: switch action, terminal(s) connection action, re-dispatching action
 - Fast DC mode available, based on Woodbury's formula for calculating post-contingency states. Note that this mode has limitations for the moment. Remedial actions are not yet taken into account, as well as contingencies on HVDC lines in AC emulation mode.

### Sensitivity analysis implementation 

 Open Load Flow both supports both AC and DC calculations. Even though it comes from the same powsybl-core API, the calculations behind are radically different. The AC post-contingency sensitivities calculation is based on the same principles than the AC security analysis. The DC post-contingency sensitivities calculation is highly optimized and fully documented [here](https://powsybl.readthedocs.io/projects/powsybl-open-loadflow/en/latest/sensitivity/index.html).

It supports all types of sensitivity factors that can be found in the API: 
- Variables: injection increase, phase angle shift, HVDC set point increase. For AC calculations only: voltage target increase of generator, static var compensator, transformers or shunt.
- Functions: the active power flow or the current on a branch. For AC calculations only: the voltage on a bus.

The following contingency types are supported:
- Branch contingencies,
- Load and generator contingencies,
- HVDC line contingency.

## Getting started

Running a load flow with PowSyBl Open Load Flow is easy. First let's start loading a IEEE 14 bus network. We first add a few Maven 
dependencies to respectively have access to network model, IEEE test networks and simple logging capabilities:

```xml
<dependency>
    <groupId>com.powsybl</groupId>
    <artifactId>powsybl-iidm-impl</artifactId>
    <version>6.5.1</version>
</dependency>
<dependency>
    <groupId>com.powsybl</groupId>
    <artifactId>powsybl-ieee-cdf-converter</artifactId>
    <version>6.5.1</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.13</version>
</dependency>
```

We are now able to load the IEEE 14 bus:
 ```java
Network network = IeeeCdfNetworkFactory.create14();
 ```

After adding a last Maven dependency on Open Load Flow implementation:
```xml
<dependency>
    <groupId>com.powsybl</groupId>
    <artifactId>powsybl-open-loadflow</artifactId>
    <version>1.13.2</version>
</dependency>
```

We can run the load flow with default parameters on the network:
```java
LoadFlow.run(network);
```

State variables and power flows computed by the load flow are have been updated inside the network model, and we can, for instance 
print on standard output buses voltage magnitude and angle:

```java
network.getBusView().getBusStream().forEach(b -> System.out.println(b.getId() + " " + b.getV() + " " + b.getAngle()));
```
## Contributing to PowSyBl Open Load Flow

PowSyBl Open Load Flow could support more features. The following list is not exhaustive and is an invitation to collaborate:

We can always increase or improves features and implementations. We have thought about:

- Improving performances of the AC security and sensitivity analysis implementations.
- Support of all remedial action types available in the API.
- Contingency propagation in AC and DC sensitivity analyses.

For more details, to report bugs or if you need more features, visit our [github](https://github.com/powsybl/powsybl-open-loadflow/issues) and do not hesitate to write new issues.


## Using Maven Wrapper
If you don't have a proper Maven installation, you could use the provided Apache Maven Wrapper scripts.
They will download a compatible maven distribution and use it automatically.

You can see the [Using Maven Wrapper](https://github.com/powsybl/powsybl-core/tree/main#using-maven-wrapper) section of the [powsybl-core](https://github.com/powsybl/powsybl-core) documentation if you want further information on this subject.
