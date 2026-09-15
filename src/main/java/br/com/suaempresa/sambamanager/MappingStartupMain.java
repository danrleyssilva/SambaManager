package br.com.suaempresa.sambamanager;

import br.com.suaempresa.sambamanager.service.AutomaticMappingRestoreService;
import br.com.suaempresa.sambamanager.service.SambaConfig;

/** Small, windowless launcher started only for the current Windows user. */
public final class MappingStartupMain {
    private MappingStartupMain() { }

    public static void main(String[] args) {
        try {
            AutomaticMappingRestoreService.restore(SambaConfig.load().server());
        } catch (Exception failure) {
            // restore() records a password-free diagnostic in the user's profile.
            System.exit(1);
        }
    }
}
