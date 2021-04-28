/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.util;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class GenerationActivePowerDistributionStep implements ActivePowerDistribution.Step {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationActivePowerDistributionStep.class);

    public enum ParticipationType {
        MAX,
        TARGET
    }

    private ParticipationType participationType;

    public GenerationActivePowerDistributionStep(ParticipationType pParticipationType) {
        this.participationType = pParticipationType;
    }

    @Override
    public String getElementType() {
        return "generation";
    }

    @Override
    public List<ParticipatingElement> getParticipatingElements(Collection<LfBus> buses) {
        return buses.stream()
                .filter(bus -> !(bus.isDisabled() || bus.isFictitious()))
                .flatMap(bus -> bus.getGenerators().stream())
                .filter(generator -> generator.isParticipating() && getParticipationFactor(generator) != 0)
                .map(generator -> new ParticipatingElement(generator, getParticipationFactor(generator)))
                .collect(Collectors.toList());
    }

    @Override
    public double run(List<ParticipatingElement> participatingElements, int iteration, double remainingMismatch) {
        // normalize participation factors at each iteration start as some
        // generators might have reach a limit and have been discarded
        ParticipatingElement.normalizeParticipationFactors(participatingElements, "generator");

        double done = 0d;
        int modifiedBuses = 0;
        int generatorsAtMax = 0;
        int generatorsAtMin = 0;
        Iterator<ParticipatingElement> it = participatingElements.iterator();
        while (it.hasNext()) {
            ParticipatingElement participatingGenerator = it.next();
            LfGenerator generator = (LfGenerator) participatingGenerator.getElement();
            double factor = participatingGenerator.getFactor();

            double minP = generator.getMinP();
            double maxP = generator.getMaxP();
            double targetP = generator.getTargetP();

            // we don't want to change the generation sign
            if (targetP < 0) {
                maxP = Math.min(maxP, 0);
            } else {
                minP = Math.max(minP, 0);
            }

            double newTargetP = targetP + remainingMismatch * factor;
            if (remainingMismatch > 0 && newTargetP > maxP) {
                newTargetP = maxP;
                generatorsAtMax++;
                it.remove();
            } else if (remainingMismatch < 0 && newTargetP < minP) {
                newTargetP = minP;
                generatorsAtMin++;
                it.remove();
            }

            if (newTargetP != targetP) {
                LOGGER.trace("Rescale '{}' active power target: {} -> {}",
                        generator.getId(), targetP * PerUnit.SB, newTargetP * PerUnit.SB);
                generator.setTargetP(newTargetP);
                done += newTargetP - targetP;
                modifiedBuses++;
            }
        }

        LOGGER.debug("{} MW / {} MW distributed at iteration {} to {} generators ({} at max power, {} at min power)",
                done * PerUnit.SB, remainingMismatch * PerUnit.SB, iteration, modifiedBuses,
                generatorsAtMax, generatorsAtMin);

        return done;
    }

    private double getParticipationFactor(LfGenerator generator) {
        double factor;
        switch (participationType) {
            case MAX:
                factor = generator.getMaxP() / generator.getDroop();
                break;
            case TARGET:
                factor = Math.abs(generator.getTargetP());
                break;
            default:
                throw new UnsupportedOperationException("Unknown balance type mode: " + participationType);
        }
        return factor;
    }
}
