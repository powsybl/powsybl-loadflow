package com.powsybl.openloadflow.network.util;

import com.powsybl.openloadflow.network.LfAsymBus;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.util.Fortescue;

import java.util.Objects;

/**
 * @author JB Heyberger <jbheyberger at gmail.com>
 */
public final class AsymUniformValueVoltageInitializer {

    private AsymUniformValueVoltageInitializer() {

    }

    public static double getMagnitude(LfBus bus, LfAsymBus asymBus, Fortescue.SequenceType sequenceType) {
        Objects.requireNonNull(bus);
        Objects.requireNonNull(asymBus);
        Objects.requireNonNull(sequenceType);

        if (asymBus.isFortescueRepresentation()) {
            if (sequenceType == Fortescue.SequenceType.NEGATIVE || sequenceType == Fortescue.SequenceType.ZERO) {
                return 0.1;
            }
            return 1;
        }

        // Three phase representation
        return 1;
    }

    public static double getAngle(LfBus bus, LfAsymBus asymBus, Fortescue.SequenceType sequenceType) {
        Objects.requireNonNull(bus);
        Objects.requireNonNull(asymBus);
        Objects.requireNonNull(sequenceType);

        if (asymBus.isFortescueRepresentation()) {
            return 0;
        }

        double phiA = 0.;
        double phiB = -2 * Math.PI / 3.;
        double phiC = 2 * Math.PI / 3.;

        // Three phase representation
        boolean pA = asymBus.isHasPhaseA();
        boolean pB = asymBus.isHasPhaseB();
        boolean pC = asymBus.isHasPhaseC();

        if (pA && pB && pC) {
            if (sequenceType == Fortescue.SequenceType.ZERO) {
                return phiA;
            } else if (sequenceType == Fortescue.SequenceType.POSITIVE) {
                return phiB;
            } else {
                return phiC;
            }
        } else if (!pA && pB && pC) {
            if (sequenceType == Fortescue.SequenceType.ZERO) {
                return phiB;
            } else if (sequenceType == Fortescue.SequenceType.POSITIVE) {
                return phiC;
            } else {
                throw new IllegalStateException("negative sequence must be excluded from a 2 phase bus at bus = " + bus.getId());
            }
        } else if (pA && !pB && pC) {
            if (sequenceType == Fortescue.SequenceType.ZERO) {
                return phiA;
            } else if (sequenceType == Fortescue.SequenceType.POSITIVE) {
                return phiC;
            } else {
                throw new IllegalStateException("negative sequence must be excluded from a 2 phase bus at bus = " + bus.getId());
            }
        } else if (pA && pB && !pC) {
            if (sequenceType == Fortescue.SequenceType.ZERO) {
                return phiA;
            } else if (sequenceType == Fortescue.SequenceType.POSITIVE) {
                return phiB;
            } else {
                throw new IllegalStateException("negative sequence must be excluded from a 2 phase bus at bus = " + bus.getId());
            }
        } else if (!pA && !pB && pC) {
            if (sequenceType == Fortescue.SequenceType.POSITIVE) {
                return phiC;
            } else {
                throw new IllegalStateException("zero and negative sequences must be excluded from a 1 phase bus at bus = " + bus.getId());
            }
        } else if (!pA && pB && !pC) {
            if (sequenceType == Fortescue.SequenceType.POSITIVE) {
                return phiB;
            } else {
                throw new IllegalStateException("zero and negative sequences must be excluded from a 1 phase bus at bus = " + bus.getId());
            }
        } else if (pA && !pB && !pC) {
            if (sequenceType == Fortescue.SequenceType.POSITIVE) {
                return phiA;
            } else {
                throw new IllegalStateException("zero and negative sequences must be excluded from a 1 phase bus at bus = " + bus.getId());
            }
        }

        return 0;
    }
}