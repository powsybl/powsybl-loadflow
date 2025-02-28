/*
 * Copyright (c) 2022-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.iidm.network.extensions.HvdcOperatorActivePowerRange;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static com.powsybl.openloadflow.util.EvaluableConstants.NAN;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class LfHvdcImpl extends AbstractElement implements LfHvdc {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfHvdcImpl.class);

    private final String id;

    private final LfBus bus1;

    private final LfBus bus2;

    private Evaluable p1 = NAN;

    private Evaluable p2 = NAN;

    private double droop = Double.NaN;

    private double p0 = Double.NaN;

    private double r = Double.NaN;

    private double nominalV = Double.NaN;

    private LfVscConverterStation converterStation1;

    private LfVscConverterStation converterStation2;

    private boolean acEmulation;

    private final double pMaxFromCS1toCS2;

    private final double pMaxFromCS2toCS1;

    public LfHvdcImpl(String id, LfBus bus1, LfBus bus2, LfNetwork network, HvdcLine hvdcLine, boolean acEmulation) {
        super(network);
        this.id = Objects.requireNonNull(id);
        this.bus1 = bus1;
        this.bus2 = bus2;
        this.nominalV = hvdcLine.getNominalV();
        this.r = hvdcLine.getR();
        HvdcAngleDroopActivePowerControl droopControl = hvdcLine.getExtension(HvdcAngleDroopActivePowerControl.class);
        this.acEmulation = acEmulation && droopControl != null && droopControl.isEnabled();
        if (this.acEmulation) {
            droop = droopControl.getDroop();
            p0 = droopControl.getP0();
        }
        HvdcOperatorActivePowerRange powerRange = hvdcLine.getExtension(HvdcOperatorActivePowerRange.class);
        if (powerRange != null) {
            pMaxFromCS1toCS2 = powerRange.getOprFromCS1toCS2();
            pMaxFromCS2toCS1 = powerRange.getOprFromCS2toCS1();
        } else {
            pMaxFromCS2toCS1 = hvdcLine.getMaxP();
            pMaxFromCS1toCS2 = hvdcLine.getMaxP();
        }

        if (this.acEmulation) {
            // power is in MW (not PU) - droop is in MW/deg
            double operatingAngleDeg = (pMaxFromCS1toCS2 + pMaxFromCS2toCS1) / droop;
            if (operatingAngleDeg < 4) {
                LOGGER.warn("Small operating angle for HVDC {} : {} deg", getId(), operatingAngleDeg);
            }
        }
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
    public LfBus getOtherBus(LfBus bus) {
        return bus.equals(bus1) ? bus2 : bus1;
    }

    @Override
    public void setDisabled(boolean disabled) {
        super.setDisabled(disabled); // for AC emulation equations only.
        if (!acEmulation && !disabled) {
            // re-active power transmission to initial target values.
            converterStation1.setTargetP(converterStation1.getInitialTargetP());
            converterStation2.setTargetP(converterStation2.getInitialTargetP());
        }
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
    public double getR() {
        return r / PerUnit.zb(nominalV);
    }

    @Override
    public double getP0() {
        return p0 / PerUnit.SB;
    }

    @Override
    public boolean isAcEmulation() {
        return acEmulation;
    }

    @Override
    public void setAcEmulation(boolean acEmulation) {
        this.acEmulation = acEmulation;
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
        this.converterStation1 = Objects.requireNonNull(converterStation1);
        converterStation1.setHvdc(this);
    }

    @Override
    public void setConverterStation2(LfVscConverterStation converterStation2) {
        this.converterStation2 = Objects.requireNonNull(converterStation2);
        converterStation2.setHvdc(this);
    }

    @Override
    public void updateState() {
        if (acEmulation) {
            ((LfVscConverterStationImpl) converterStation1).getStation().getTerminal().setP(p1.eval() * PerUnit.SB);
            ((LfVscConverterStationImpl) converterStation2).getStation().getTerminal().setP(p2.eval() * PerUnit.SB);
        }
    }

    @Override
    public double getPMaxFromCS1toCS2() {
        return Double.isNaN(pMaxFromCS1toCS2) ? Double.MAX_VALUE : pMaxFromCS1toCS2 / PerUnit.SB;
    }

    @Override
    public double getPMaxFromCS2toCS1() {
        return Double.isNaN(pMaxFromCS1toCS2) ? Double.MAX_VALUE : pMaxFromCS2toCS1 / PerUnit.SB;
    }
}
