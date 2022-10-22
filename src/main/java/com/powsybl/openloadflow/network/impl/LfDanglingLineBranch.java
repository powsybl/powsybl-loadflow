/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.DanglingLine;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.PiModel;
import com.powsybl.openloadflow.network.SimplePiModel;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.results.BranchResult;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class LfDanglingLineBranch extends AbstractImpedantLfBranch {

    private final WeakReference<DanglingLine> danglingLineRef;

    protected LfDanglingLineBranch(LfNetwork network, LfBus bus1, LfBus bus2, PiModel piModel, DanglingLine danglingLine) {
        super(network, bus1, bus2, piModel);
        this.danglingLineRef = new WeakReference<>(danglingLine);
    }

    public static LfDanglingLineBranch create(DanglingLine danglingLine, LfNetwork network, LfBus bus1, LfBus bus2) {
        Objects.requireNonNull(danglingLine);
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        double nominalV = danglingLine.getTerminal().getVoltageLevel().getNominalV();
        double zb = nominalV * nominalV / PerUnit.SB;
        PiModel piModel = new SimplePiModel()
                .setR(danglingLine.getR() / zb)
                .setX(danglingLine.getX() / zb)
                .setG1(danglingLine.getG() / 2 * zb)
                .setG2(danglingLine.getG() / 2 * zb)
                .setB1(danglingLine.getB() / 2 * zb)
                .setB2(danglingLine.getB() / 2 * zb);
        return new LfDanglingLineBranch(network, bus1, bus2, piModel, danglingLine);
    }

    private DanglingLine getDanglingLine() {
        return Objects.requireNonNull(danglingLineRef.get(), "Reference has been garbage collected");
    }

    @Override
    public String getId() {
        return getDanglingLine().getId();
    }

    @Override
    public BranchType getBranchType() {
        return BranchType.DANGLING_LINE;
    }

    @Override
    public boolean hasPhaseControlCapability() {
        return false;
    }

    @Override
    public BranchResult createBranchResult(double preContingencyBranchP1, double preContingencyBranchOfContingencyP1, boolean createExtension) {
        throw new PowsyblException("Unsupported type of branch for branch result: " + getId());
    }

    @Override
    public List<LfLimit> getLimits1(final LimitType type) {
        var danglingLine = getDanglingLine();
        switch (type) {
            case ACTIVE_POWER:
                return getLimits1(type, danglingLine.getActivePowerLimits().orElse(null));
            case APPARENT_POWER:
                return getLimits1(type, danglingLine.getApparentPowerLimits().orElse(null));
            case CURRENT:
                return getLimits1(type, danglingLine.getCurrentLimits().orElse(null));
            case VOLTAGE:
            default:
                throw new UnsupportedOperationException(String.format("Getting %s limits is not supported.", type.name()));
        }
    }

    @Override
    public void updateState(boolean phaseShifterRegulationOn, boolean isTransformerVoltageControlOn, boolean dc) {
        updateFlows(p1.eval(), q1.eval(), Double.NaN, Double.NaN);
    }

    @Override
    public void updateFlows(double p1, double q1, double p2, double q2) {
        // Network side is always on side 1.
        getDanglingLine().getTerminal().setP(p1 * PerUnit.SB)
                .setQ(q1 * PerUnit.SB);
    }
}
