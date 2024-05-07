/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.network.ElementType;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public enum AcEquationType implements Quantity {
    BUS_TARGET_P("bus_target_p", ElementType.BUS), // bus active power target
    BUS_TARGET_Q("bus_target_q", ElementType.BUS), // bus reactive power target
    BUS_TARGET_V("bus_target_v", ElementType.BUS), // bus voltage magnitude control
    BUS_TARGET_PHI("bus_target_\u03C6", ElementType.BUS), // slack bus voltage angle target
    SHUNT_TARGET_B("shunt_target_b", ElementType.SHUNT_COMPENSATOR), // shunt susceptance
    BRANCH_TARGET_P("branch_target_p", ElementType.BRANCH), // phase shifter active flow control
    BRANCH_TARGET_Q("branch_target_q", ElementType.BRANCH), // generator reactive power control
    BRANCH_TARGET_ALPHA1("branch_target_\u03B1", ElementType.BRANCH), // phase shifter constant shift
    BRANCH_TARGET_RHO1("branch_target_\u03C1", ElementType.BRANCH), // transformer constant voltage control
    DISTR_Q("distr_q", ElementType.BUS), // remote control reactive power distribution, both for voltage and reactive power control
    ZERO_V("zero_v", ElementType.BRANCH), // zero impedance branch, voltage magnitude equality
    ZERO_PHI("zero_\u03C6", ElementType.BRANCH), // zero impedance branch, voltage angle equality
    DISTR_RHO("distr_\u03C1", ElementType.BRANCH), // remote transformer voltage control ratio distribution
    DISTR_SHUNT_B("distr_b", ElementType.SHUNT_COMPENSATOR), // shunt remote voltage control susceptance distribution
    DUMMY_TARGET_P("dummy_target_p", ElementType.BRANCH),
    DUMMY_TARGET_Q("dummy_target_q", ElementType.BRANCH),
    BUS_DISTR_SLACK_P("bus_distr_slack_p", ElementType.BUS), // multiple slack buses distribution
    BUS_TARGET_IX_ZERO("bus_target_ix_zero", ElementType.BUS), // bus real part current target for zero sequence
    BUS_TARGET_IY_ZERO("bus_target_iy_zero", ElementType.BUS), // bus imaginary part current target for zero sequence
    BUS_TARGET_IX_NEGATIVE("bus_target_ix_negative", ElementType.BUS), // bus real part current target for negative sequence
    BUS_TARGET_IY_NEGATIVE("bus_target_iy_negative", ElementType.BUS); // bus imaginary part current target for negative sequence

    private final String symbol;

    private final ElementType elementType;

    AcEquationType(String symbol, ElementType elementType) {
        this.symbol = symbol;
        this.elementType = elementType;
    }

    @Override
    public String getSymbol() {
        return symbol;
    }

    @Override
    public ElementType getElementType() {
        return elementType;
    }
}
