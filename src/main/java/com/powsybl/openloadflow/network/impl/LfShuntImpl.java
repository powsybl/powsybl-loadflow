/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.ShuntCompensatorLinearModel;
import com.powsybl.iidm.network.ShuntCompensatorModel;
import com.powsybl.iidm.network.ShuntCompensatorNonLinearModel;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class LfShuntImpl extends AbstractLfShunt {

    private final class ControllerImpl extends Controller {

        private final Ref<ShuntCompensator> shuntCompensatorRef;

        private ControllerImpl(Ref<ShuntCompensator> shuntCompensatorRef, List<Double> sectionsB, List<Double> sectionsG, int position) {
            super(shuntCompensatorRef.get().getId(), sectionsB, sectionsG, position);
            this.shuntCompensatorRef = shuntCompensatorRef;
        }

        private Ref<ShuntCompensator> getShuntCompensatorRef() {
            return shuntCompensatorRef;
        }

        @Override
        public Optional<Direction> updateSectionB(double deltaB, int maxSectionShift, AllowedDirection allowedDirection) {
            Optional<Direction> direction = super.updateSectionB(deltaB, maxSectionShift, allowedDirection);
            if (direction.isPresent()) { // it means position has changed
                setG(controllers.stream().mapToDouble(Controller::getG).sum());
                setB(controllers.stream().mapToDouble(Controller::getB).sum());
            }
            return direction;
        }

        @Override
        public void updateSectionB(int newPosition) {
            super.updateSectionB(newPosition);
            setG(controllers.stream().mapToDouble(Controller::getG).sum());
            setB(controllers.stream().mapToDouble(Controller::getB).sum());
        }
    }

    private final List<Ref<ShuntCompensator>> shuntCompensatorsRefs;

    private final LfBus bus;

    private ShuntVoltageControl voltageControl;

    private boolean voltageControlCapability;

    private boolean voltageControlEnabled = false;

    private final List<Controller> controllers = new ArrayList<>();

    private double b;

    private final double zb;

    private double g;

    public LfShuntImpl(List<ShuntCompensator> shuntCompensators, LfNetwork network, LfBus bus, boolean voltageControlCapability,
                       LfNetworkParameters parameters, LfTopoConfig topoConfig) {
        // if withVoltageControl equals to true, all shunt compensators that are listed must control voltage.
        // if withVoltageControl equals to false, all shunt compensators that are listed will be treated as fixed shunt
        // compensators.
        super(bus, network);
        shuntCompensatorsRefs = Objects.requireNonNull(shuntCompensators).stream()
                .map(sc -> Ref.create(sc, parameters.isCacheEnabled()))
                .collect(Collectors.toList());
        if (shuntCompensators.isEmpty()) {
            throw new IllegalArgumentException("Empty shunt compensator list");
        }
        this.bus = Objects.requireNonNull(bus);
        this.voltageControlCapability = voltageControlCapability;
        double nominalV = shuntCompensators.get(0).getTerminal().getVoltageLevel().getNominalV(); // has to be the same for all shunts
        zb = PerUnit.zb(nominalV);
        b = computeB(shuntCompensators, zb);
        g = computeG(shuntCompensators, zb);

        boolean keepSections = shuntCompensators.stream().map(ShuntCompensator::getId).anyMatch(id -> topoConfig.isOperatedShunt(id));

        if (voltageControlCapability || keepSections) {
            shuntCompensatorsRefs.forEach(shuntCompensatorRef -> {
                var shuntCompensator = shuntCompensatorRef.get();
                List<Double> sectionsB = new ArrayList<>(1);
                List<Double> sectionsG = new ArrayList<>(1);
                sectionsB.add(0.0);
                sectionsG.add(0.0);
                ShuntCompensatorModel model = shuntCompensator.getModel();
                switch (shuntCompensator.getModelType()) {
                    case LINEAR:
                        ShuntCompensatorLinearModel linearModel = (ShuntCompensatorLinearModel) model;
                        for (int section = 1; section <= shuntCompensator.getMaximumSectionCount(); section++) {
                            sectionsB.add(linearModel.getBPerSection() * section * zb);
                            sectionsG.add(linearModel.getGPerSection() * section * zb);
                        }
                        break;
                    case NON_LINEAR:
                        ShuntCompensatorNonLinearModel nonLinearModel = (ShuntCompensatorNonLinearModel) model;
                        for (int section = 0; section < shuntCompensator.getMaximumSectionCount(); section++) {
                            sectionsB.add(nonLinearModel.getAllSections().get(section).getB() * zb);
                            sectionsG.add(nonLinearModel.getAllSections().get(section).getG() * zb);
                        }
                        break;
                }
                controllers.add(new ControllerImpl(shuntCompensatorRef, sectionsB, sectionsG, shuntCompensator.getSectionCount()));
            });
            // Controllers are always enabled, a contingency with shunt compensator with voltage control on is not supported yet.
            controllers.sort(Comparator.comparingDouble(Controller::getBMagnitude).reversed());
        }
    }

    private static double computeG(List<ShuntCompensator> shuntCompensators, double zb) {
        return zb * shuntCompensators.stream()
                .mapToDouble(ShuntCompensator::getG)
                .sum();
    }

    private static double computeB(List<ShuntCompensator> shuntCompensators, double zb) {
        return zb * shuntCompensators.stream()
                .mapToDouble(ShuntCompensator::getB)
                .sum();
    }

    @Override
    public ElementType getType() {
        return ElementType.SHUNT_COMPENSATOR;
    }

    @Override
    public String getId() {
        return controllers.isEmpty() ? bus.getId() + "_shunt_compensators" : bus.getId() + "_controller_shunt_compensators";
    }

    @Override
    public List<String> getOriginalIds() {
        return shuntCompensatorsRefs.stream().map(scRef -> scRef.get().getId()).collect(Collectors.toList());
    }

    @Override
    public double getB() {
        return b;
    }

    @Override
    public void setB(double b) {
        if (b != this.b) {
            this.b = b;
            for (LfNetworkListener listener : getNetwork().getListeners()) {
                listener.onShuntSusceptanceChange(this, b);
            }
        }
    }

    @Override
    public double getG() {
        return g;
    }

    @Override
    public void setG(double g) {
        this.g = g;
    }

    @Override
    public boolean hasVoltageControlCapability() {
        return voltageControlCapability;
    }

    @Override
    public void setVoltageControlCapability(boolean voltageControlCapability) {
        this.voltageControlCapability = voltageControlCapability;
    }

    @Override
    public boolean isVoltageControlEnabled() {
        return voltageControlEnabled;
    }

    @Override
    public void setVoltageControlEnabled(boolean voltageControlEnabled) {
        if (this.voltageControlEnabled != voltageControlEnabled) {
            this.voltageControlEnabled = voltageControlEnabled;
            for (LfNetworkListener listener : network.getListeners()) {
                listener.onShuntVoltageControlChange(this, voltageControlEnabled);
            }
        }
    }

    @Override
    public Optional<ShuntVoltageControl> getVoltageControl() {
        return Optional.ofNullable(voltageControl);
    }

    @Override
    public void setVoltageControl(ShuntVoltageControl voltageControl) {
        this.voltageControl = voltageControl;
    }

    public List<Controller> getControllers() {
        return controllers;
    }

    private void roundBToClosestSection(double b, Controller controller) {
        List<Double> sections = controller.getSectionsB();
        // find tap position with the closest b value
        double smallestDistance = Math.abs(b - sections.get(controller.getPosition()));
        for (int s = 0; s < sections.size(); s++) {
            double distance = Math.abs(b - sections.get(s));
            if (distance < smallestDistance) {
                controller.setPosition(s);
                smallestDistance = distance;
            }
        }
        LOGGER.trace("Round B shift of shunt '{}': {} -> {}", controller.getId(), b * zb, controller.getB() * zb);
    }

    @Override
    public double dispatchB() {
        double residueB = b;
        int remainingControllers = controllers.size();
        for (Controller controller : controllers) {
            double bToDispatchByController = residueB / remainingControllers--;
            roundBToClosestSection(bToDispatchByController, controller);
            residueB -= controller.getB();
        }
        b = controllers.stream().mapToDouble(Controller::getB).sum();
        return residueB;
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters) {
        if (parameters.isDc()) {
            for (var scRef : shuntCompensatorsRefs) {
                var sc = scRef.get();
                sc.getTerminal().setP(0);
            }
        } else {
            double vSquare = bus.getV() * bus.getV() * bus.getNominalV() * bus.getNominalV();
            if (!voltageControlCapability) {
                for (var scRef : shuntCompensatorsRefs) {
                    var sc = scRef.get();
                    sc.getTerminal().setP(sc.getG() * vSquare);
                    sc.getTerminal().setQ(-sc.getB() * vSquare);
                }
            } else {
                for (Controller controller : controllers) {
                    ShuntCompensator sc = ((ControllerImpl) controller).getShuntCompensatorRef().get();
                    sc.getTerminal().setP(controller.getG() * vSquare / zb);
                    sc.getTerminal().setQ(-controller.getB() * vSquare / zb);
                    sc.setSectionCount(controller.getPosition());
                }
            }
        }
    }

    @Override
    public void reInit() {
        if (voltageControlCapability) {
            throw new PowsyblException("Cannot re-init a shunt compensator with voltage control capabilities");
        }
        List<ShuntCompensator> shuntCompensators = shuntCompensatorsRefs.stream().map(Ref::get).collect(Collectors.toList());
        setB(computeB(shuntCompensators, zb));
        setG(computeG(shuntCompensators, zb));
    }
}
