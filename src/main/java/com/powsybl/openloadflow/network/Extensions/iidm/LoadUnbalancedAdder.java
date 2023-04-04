package com.powsybl.openloadflow.network.Extensions.iidm;

import com.powsybl.commons.extensions.AbstractExtensionAdder;
import com.powsybl.iidm.network.Load;

/**
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public class LoadUnbalancedAdder extends AbstractExtensionAdder<Load, LoadUnbalanced> {

    private double deltaPa = 0.;
    private double deltaQa = 0.;
    private double deltaPb = 0.;
    private double deltaQb = 0.;
    private double deltaPc = 0.;
    private double deltaQc = 0.;

    public LoadUnbalancedAdder(Load load) {
        super(load);
    }

    @Override
    public Class<? super LoadUnbalanced> getExtensionClass() {
        return LoadUnbalanced.class;
    }

    @Override
    protected LoadUnbalanced createExtension(Load load) {
        return new LoadUnbalanced(load, deltaPa, deltaQa, deltaPb, deltaQb, deltaPc, deltaQc);
    }

    public LoadUnbalancedAdder withPa(double deltaPa) {
        this.deltaPa = deltaPa;
        return this;
    }

    public LoadUnbalancedAdder withQa(double deltaQa) {
        this.deltaQa = deltaQa;
        return this;
    }

    public LoadUnbalancedAdder withPb(double deltaPb) {
        this.deltaPb = deltaPb;
        return this;
    }

    public LoadUnbalancedAdder withQb(double deltaQb) {
        this.deltaQb = deltaQb;
        return this;
    }

    public LoadUnbalancedAdder withPc(double deltaPc) {
        this.deltaPc = deltaPc;
        return this;
    }

    public LoadUnbalancedAdder withQc(double deltaQc) {
        this.deltaQc = deltaQc;
        return this;
    }
}
