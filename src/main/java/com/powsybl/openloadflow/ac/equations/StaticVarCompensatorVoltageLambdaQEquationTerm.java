package com.powsybl.openloadflow.ac.equations;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Load;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.PerUnit;
import com.powsybl.openloadflow.network.impl.AbstractLfBus;
import com.powsybl.openloadflow.network.impl.LfStaticVarCompensatorImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class StaticVarCompensatorVoltageLambdaQEquationTerm extends AbstractNamedEquationTerm {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaticVarCompensatorVoltageLambdaQEquationTerm.class);

    private final List<LfStaticVarCompensatorImpl> lfStaticVarCompensators;

    private final LfBus bus;

    private final EquationSystem equationSystem;

    private final Variable vVar;

    private final Variable phiVar;

    private final List<Variable> variables;

    private double targetV;

    private double dfdU;

    private double dfdph;

    public StaticVarCompensatorVoltageLambdaQEquationTerm(List<LfStaticVarCompensatorImpl> lfStaticVarCompensators, LfBus bus, VariableSet variableSet, EquationSystem equationSystem) {
        this.lfStaticVarCompensators = lfStaticVarCompensators;
        this.bus = bus;
        this.equationSystem = equationSystem;
        vVar = variableSet.getVariable(bus.getNum(), VariableType.BUS_V);
        phiVar = variableSet.getVariable(bus.getNum(), VariableType.BUS_PHI);
        variables = Arrays.asList(vVar, phiVar);
    }

    @Override
    public SubjectType getSubjectType() {
        return SubjectType.BUS;
    }

    @Override
    public int getSubjectNum() {
        return bus.getNum();
    }

    @Override
    public List<Variable> getVariables() {
        return variables;
    }

    @Override
    public void update(double[] x) {
        Objects.requireNonNull(x);
        if (lfStaticVarCompensators.size() > 1) {
            throw new PowsyblException("Bus PVLQ (" + bus.getId() + ") not supported yet as it contains more than one staticVarCompensator");
        }
        LfStaticVarCompensatorImpl lfStaticVarCompensator = lfStaticVarCompensators.get(0);
        double slope = lfStaticVarCompensator.getSlope();
        Equation reactiveEquation = equationSystem.createEquation(bus.getNum(), EquationType.BUS_Q);

        double[] result = sumEvalAndDerOnBranchTermsFromEquationBUSQ(x);
        double evalQbus = result[0];
        double dQdU = result[1];
        double dQdtheta = result[2];

        AbstractLfBus abstractLfBus = (AbstractLfBus) bus;
        double sumQloads = getSumQloads(abstractLfBus);
        double sumQgenerators = getSumQgenerators(abstractLfBus);

        // f(U, theta) = U + lambda * Q(U, theta)
        // Qbus = Qsvc - Qload + Qgenerator d'où : Q(U, theta) = Qsvc =  Qbus + Qload - Qgenerator
        targetV = x[vVar.getRow()] + slope * (evalQbus + sumQloads - sumQgenerators);
        // dfdU = 1 + lambda dQdU
        dfdU = 1 + slope * dQdU;
        // dfdtheta = lambda * dQdtheta
        dfdph = slope * dQdtheta;
        LOGGER.debug("x = {} ; evalQbus = {} ; targetV = {} ; evalQbus - bus.getLoadTargetQ() = {}",
                x[vVar.getRow()] * bus.getNominalV(), evalQbus * PerUnit.SB, targetV * bus.getNominalV(), (evalQbus - bus.getLoadTargetQ()) * PerUnit.SB);
    }

    /**
     *
     * @param x variable value vector initialize with a VoltageInitializer
     * @return sum evaluation and derivatives on branch terms from BUS_Q equation
     */
    private double[] sumEvalAndDerOnBranchTermsFromEquationBUSQ(double[] x) {
        Equation reactiveEquation = equationSystem.createEquation(bus.getNum(), EquationType.BUS_Q);
        double evalQbus = 0;
        double dQdU = 0;
        double dQdtheta = 0;
        for (EquationTerm equationTerm : reactiveEquation.getTerms()) {
            if (equationTerm.isActive() &&
                    (equationTerm instanceof ClosedBranchSide1ReactiveFlowEquationTerm
                            || equationTerm instanceof ClosedBranchSide2ReactiveFlowEquationTerm)) {
                equationTerm.update(x);
                evalQbus += equationTerm.eval();
                dQdU += equationTerm.der(vVar);
                dQdtheta += equationTerm.der(phiVar);
            }
        }
        return new double[]{evalQbus, dQdU, dQdtheta};
    }

    private double getSumQloads(AbstractLfBus abstractLfBus) {
        double sumQloads = 0;
        LOGGER.trace("abstractLfBus.getTargetQ() = {} ; abstractLfBus.getCalculatedQ() = {} ; abstractLfBus.getLoadTargetQ() = {} ; abstractLfBus.getGenerationTargetQ() = {}",
                abstractLfBus.getTargetQ(), abstractLfBus.getCalculatedQ(), abstractLfBus.getLoadTargetQ(), abstractLfBus.getGenerationTargetQ());
        for (Load load : abstractLfBus.getLoads()) {
            LOGGER.debug("load.getQ0() = {} ; load.getTerminal().getQ() = {}", load.getQ0(), load.getTerminal().getQ());
            sumQloads += load.getQ0();
        }
        return sumQloads / PerUnit.SB;
    }

    private double getSumQgenerators(AbstractLfBus abstractLfBus) {
        double sumQgenerators = 0;
        LOGGER.trace("abstractLfBus.getTargetQ() = {} ; abstractLfBus.getCalculatedQ() = {} ; abstractLfBus.getLoadTargetQ() = {} ; abstractLfBus.getGenerationTargetQ() = {}",
                abstractLfBus.getTargetQ(), abstractLfBus.getCalculatedQ(), abstractLfBus.getLoadTargetQ(), abstractLfBus.getGenerationTargetQ());
        for (LfGenerator lfGenerator : abstractLfBus.getGenerators()) {
            if (!lfGenerator.hasVoltageControl()) {
                LOGGER.debug("lfGenerator.getTargetQ() = {} ; lfGenerator.getCalculatedQ() = {}", lfGenerator.getTargetQ(), lfGenerator.getCalculatedQ());
                sumQgenerators += lfGenerator.getTargetQ();
            }
        }
        return sumQgenerators;
    }

    @Override
    public double eval() {
        return targetV;
    }

    @Override
    public double der(Variable variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(vVar)) {
            return dfdU;
        } else if (variable.equals(phiVar)) {
            return dfdph;
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    public boolean hasRhs() {
        return false;
    }

    @Override
    public double rhs() {
        return 0;
    }

    @Override
    protected String getName() {
        return "ac_static_var_compensator";
    }
}
