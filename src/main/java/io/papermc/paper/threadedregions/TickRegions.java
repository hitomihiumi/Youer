package io.papermc.paper.threadedregions;

// placeholder class for Folia
public class TickRegions {

    public static int getRegionChunkShift() {
        // Youer: temporarily hardcoded for M3 (vendored-package rebase milestone) - the full Moonrise
        // chunk-system rewrite is out of scope per plan decision B3. This is a placeholder for Folia
        // (not used by Youer) whose upstream value is ThreadedTicketLevelPropagator.SECTION_SHIFT.
        return 4;
    }

}
