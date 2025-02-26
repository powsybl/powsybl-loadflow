/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.EvaluableConstants;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractLfShunt extends AbstractElement implements LfShunt {

    private final LfBus bus;

    private Evaluable q = EvaluableConstants.NAN;

    private Evaluable p = EvaluableConstants.NAN;

    protected AbstractLfShunt(LfBus bus, LfNetwork network) {
        super(network);
        this.bus = Objects.requireNonNull(bus);
    }

    @Override
    public LfBus getBus() {
        return bus;
    }

    @Override
    public Evaluable getQ() {
        return q;
    }

    @Override
    public void setQ(Evaluable q) {
        this.q = q;
    }

    @Override
    public Evaluable getP() {
        return p;
    }

    @Override
    public void setP(Evaluable p) {
        this.p = p;
    }
}
