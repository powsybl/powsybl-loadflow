package com.powsybl.openloadflow.network.action;

import com.powsybl.action.DanglingLineAction;
import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Alice Caron {@literal <alice.caron at rte-france.com>}
 */
public class LfDanglingLineAction extends AbstractLfAction<DanglingLineAction> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfDanglingLineAction.class);

    private final Network network;

    public LfDanglingLineAction(String id, DanglingLineAction action, Network network) {
        super(id, action);
        this.network = network;
    }

    @Override
    public boolean apply(LfNetwork lfNetwork, LfContingency lfContingency, LfNetworkParameters lfNetworkParameters) {
        DanglingLine danglingLine = network.getDanglingLine(action.getDanglingLineId());

        if (danglingLine != null) {
            LfBranch lfBranch = lfNetwork.getBranchById(danglingLine.getId());
            if (lfBranch != null && lfBranch.getBranchType().equals(LfBranch.BranchType.DANGLING_LINE)) {
                List<LfLoad> lfLoads = lfBranch.getBus2().getLoads();
                if (lfLoads.size() == 1 && action.getActivePowerValue().isPresent() && action.getReactivePowerValue().isPresent()) {
                    LfLoad lfLoad = lfLoads.get(0);

                    double activePowerValue = action.isRelativeValue() ? action.getActivePowerValue().getAsDouble() + lfLoad.getTargetP() : action.getActivePowerValue().getAsDouble();
                    lfLoad.setTargetP(activePowerValue / PerUnit.SB);

                    double reactivePowerValue = action.isRelativeValue() ? action.getReactivePowerValue().getAsDouble() + lfLoad.getTargetQ() : action.getReactivePowerValue().getAsDouble();
                    lfLoad.setTargetQ(reactivePowerValue / PerUnit.SB);

                    return true;
                }
                return false;
            }
            LOGGER.warn("Dangling line action {}: branch matching dangling line id {} is not a dangling line", action.getId(), action.getDanglingLineId());
            return false;
        }
        LOGGER.warn("Dangling line action {}: branch matching dangling line id {} not found", action.getId(), action.getDanglingLineId());
        return false;
    }
}
