package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;

import java.util.List;
import java.util.Objects;

public abstract class AbstractEquivalentShuntIxEquationTerm extends AbstractEquivalentShuntEquationTerm {

    private final List<Variable<AcVariableType>> variables;

    public AbstractEquivalentShuntIxEquationTerm(LfBus bus, VariableSet<AcVariableType> variableSet, DisymAcSequenceType sequenceType) {
        super(bus, variableSet, sequenceType);
        variables = List.of(vVar, phVar);
    }

    // By definition :
    // I is the current flowing out of the node in the shunt equipment
    // I = y.V with y = g+jb
    // Therefore Ix + jIy = g.Vx - b.Vy + j(g.Vy + b.Vx)
    // then Ix = g.Vmagnitude.cos(theta) - b.Vmagnitude.sin(theta)

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    // TODO : uniformize g and b with Iy
    protected double g() {
        return 1.;
    } // TODO : check acceptable large value for target V close to zero

    protected double b() {
        return 1.;
    } // TODO : check acceptable large value for target V close to zero

    private static double ix(double v, double phi, double g, double b) {
        return g * v * Math.cos(phi) - b * v * Math.sin(phi);
    }

    private static double dixdv(double phi, double g, double b) {
        return g * Math.cos(phi) - b * Math.sin(phi);
    }

    private static double dixdph(double v, double phi, double g, double b) {
        return -g * v * Math.sin(phi) - b * v * Math.cos(phi);
    }

    @Override
    public double eval() {
        return ix(v(), ph(), g(), b());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(vVar)) {
            return dixdv(ph(), g(), b());
        } else if (variable.equals(phVar)) {
            return dixdph(v(), ph(), g(), b());
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }
}
