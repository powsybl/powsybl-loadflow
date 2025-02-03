# Inputs

To run a PowSyBl Open Load Flow security analysis, you have to precise some inputs that are documented in [PowSyBl Core](inv:powsyblcore:*:*#simulation/security/index).

## Implemented remedial actions

With Open Load Flow only the following remedial actions are currently implemented:

- `LoadAction`
- `SwitchAction`
- `TerminalsConnectionAction`
- `PhaseTapChangerTapPositionAction`
- `RatioTapChangerTapPositionAction`
- `ShuntCompensatorPositionAction`
- `GeneratorAction`
- `HvdcAction`

Note: Some limitations in the use of these actions exist, please read the documentation about the [security analysis specific parameters](parameters).
