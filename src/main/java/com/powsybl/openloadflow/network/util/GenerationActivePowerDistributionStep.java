/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * Copyright (c) 2023, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.util;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Caio Luke {@literal <caio.luke at artelys.com>}
 */
public class GenerationActivePowerDistributionStep implements ActivePowerDistribution.Step {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationActivePowerDistributionStep.class);

    public enum ParticipationType {
        MAX,
        TARGET,
        PARTICIPATION_FACTOR,
        REMAINING_MARGIN
    }

    private final ParticipationType participationType;

    private final boolean useActiveLimits;

    public GenerationActivePowerDistributionStep(ParticipationType pParticipationType, boolean useActiveLimits) {
        this.participationType = pParticipationType;
        this.useActiveLimits = useActiveLimits;
    }

    @Override
    public String getElementType() {
        return "generation";
    }

    @Override
    public List<ParticipatingElement> getParticipatingElements(Collection<LfBus> buses, double activePowerMismatch) {
        return buses.stream()
                .filter(bus -> bus.isParticipating() && !bus.isDisabled() && !bus.isFictitious())
                .flatMap(bus -> bus.getGenerators().stream())
                .map(gen -> {
                    double factor = getParticipationFactor(gen, activePowerMismatch);
                    if (isParticipating(gen) && factor != 0) {
                        return new ParticipatingElement(gen, factor);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    public double run(List<ParticipatingElement> participatingElements, int iteration, double remainingMismatch) {
        // normalize participation factors at each iteration start as some
        // generators might have reach a limit and have been discarded
        ParticipatingElement.normalizeParticipationFactors(participatingElements);

        double done = 0d;
        double mismatch = remainingMismatch;
        if (iteration == 0) {
            // "undo" everything from targetP to go back to initialP
            for (ParticipatingElement participatingGenerator : participatingElements) {
                LfGenerator generator = (LfGenerator) participatingGenerator.getElement();
                done += generator.getInitialTargetP() - generator.getTargetP();
                mismatch -= generator.getInitialTargetP() - generator.getTargetP();
                generator.setTargetP(generator.getInitialTargetP());
            }
        }

        int modifiedBuses = 0;
        int generatorsAtMax = 0;
        int generatorsAtMin = 0;
        Iterator<ParticipatingElement> it = participatingElements.iterator();
        while (it.hasNext()) {
            ParticipatingElement participatingGenerator = it.next();
            LfGenerator generator = (LfGenerator) participatingGenerator.getElement();
            double factor = participatingGenerator.getFactor();

            double minTargetP = useActiveLimits ? generator.getMinTargetP() : -Double.MAX_VALUE;
            double maxTargetP = useActiveLimits ? generator.getMaxTargetP() : Double.MAX_VALUE;
            double targetP = generator.getTargetP();

            // we don't want to change the generation sign
            if (targetP < 0) {
                maxTargetP = Math.min(maxTargetP, 0);
            } else {
                minTargetP = Math.max(minTargetP, 0);
            }

            double newTargetP = targetP + mismatch * factor;
            if (mismatch > 0 && newTargetP > maxTargetP) {
                newTargetP = maxTargetP;
                generatorsAtMax++;
                it.remove();
            } else if (mismatch < 0 && newTargetP < minTargetP) {
                newTargetP = minTargetP;
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
                done * PerUnit.SB, mismatch * PerUnit.SB, iteration, modifiedBuses,
                generatorsAtMax, generatorsAtMin);

        return done;
    }

    private double getParticipationFactor(LfGenerator generator, double activePowerMismatch) {
        return switch (participationType) {
            case MAX -> generator.getMaxP() / generator.getDroop();
            case TARGET -> Math.abs(generator.getTargetP());
            case PARTICIPATION_FACTOR -> generator.getParticipationFactor();
            case REMAINING_MARGIN -> activePowerMismatch > 0 ? Math.max(0.0, generator.getMaxP() - generator.getTargetP()) : Math.max(0.0, generator.getTargetP() - generator.getMinP());
        };
    }

    private boolean isParticipating(LfGenerator generator) {
        // check first if generator is set to be participating
        if (!generator.isParticipating()) {
            return false;
        }
        // then depending on participation type, a generator may be found to not participate
        switch (participationType) {
            case MAX:
                return generator.getDroop() != 0;
            case PARTICIPATION_FACTOR:
                return generator.getParticipationFactor() > 0;
            case TARGET,
                 REMAINING_MARGIN:
                // nothing more to do here: the check whether TargetP is within Pmin-Pmax range
                // was already made in AbstractLfGenerator#checkActivePowerControl
                // whose result is reflected in generator.isParticipating()
                return true;
            default:
                throw new UnsupportedOperationException("Unknown balance type mode: " + participationType);
        }
    }
}
