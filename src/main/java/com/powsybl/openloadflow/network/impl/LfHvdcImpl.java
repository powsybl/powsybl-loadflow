/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.Objects;

import static com.powsybl.openloadflow.util.EvaluableConstants.NAN;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public class LfHvdcImpl extends AbstractElement implements LfHvdc {

    private final String id;

    private final LfBus bus1;

    private final LfBus bus2;

    private Evaluable p1 = NAN;

    private Evaluable p2 = NAN;

    private final double droop;

    private final double p0;

    private LfVscConverterStation converterStation1;

    private LfVscConverterStation converterStation2;

    public LfHvdcImpl(String id, LfBus bus1, LfBus bus2, LfNetwork network, HvdcAngleDroopActivePowerControl control) {
        super(network);
        this.id = Objects.requireNonNull(id);
        this.bus1 = bus1;
        this.bus2 = bus2;
        Objects.requireNonNull(control);
        droop = control.getDroop();
        p0 = control.getP0();
    }

    @Override
    public ElementType getType() {
        return ElementType.HVDC;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public LfBus getBus1() {
        return this.bus1;
    }

    @Override
    public LfBus getBus2() {
        return this.bus2;
    }

    @Override
    public void setP1(Evaluable p1) {
        this.p1 = Objects.requireNonNull(p1);
    }

    @Override
    public Evaluable getP1() {
        return this.p1;
    }

    @Override
    public void setP2(Evaluable p2) {
        this.p2 = Objects.requireNonNull(p2);
    }

    @Override
    public Evaluable getP2() {
        return this.p2;
    }

    @Override
    public double getDroop() {
        return droop / PerUnit.SB;
    }

    @Override
    public double getP0() {
        return p0 / PerUnit.SB;
    }

    @Override
    public LfVscConverterStation getConverterStation1() {
        return converterStation1;
    }

    @Override
    public LfVscConverterStation getConverterStation2() {
        return converterStation2;
    }

    @Override
    public void setConverterStation1(LfVscConverterStation converterStation1) {
        this.converterStation1 = converterStation1;
        converterStation1.setTargetP(0);
    }

    @Override
    public void setConverterStation2(LfVscConverterStation converterStation2) {
        this.converterStation2 = converterStation2;
        converterStation2.setTargetP(0);
    }

    @Override
    public void updateState() {
        // Should be done before updating state of generators.
        converterStation1.setTargetP(-p1.eval());
        converterStation2.setTargetP(-p2.eval());
    }
}
