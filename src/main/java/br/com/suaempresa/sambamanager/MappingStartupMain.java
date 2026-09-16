package br.com.suaempresa.sambamanager;

import br.com.suaempresa.sambamanager.service.AutomaticMappingRestoreService;
import br.com.suaempresa.sambamanager.service.SambaConfig;

/** Small, windowless launcher started only for the current Windows user. */
public final class MappingStartupMain {
    private MappingStartupMain() { }

    public static void main(String[] args) {
        AutomaticMappingRestoreService.recordStartupEvent("Auxiliar de reconexão iniciado.");
        try {
            AutomaticMappingRestoreService.restore(SambaConfig.load().server());
        } catch (Exception failure) {
            AutomaticMappingRestoreService.recordStartupEvent("Auxiliar encerrado com falha: "
                    + failure.getClass().getSimpleName() + ".");
            System.exit(1);
        }
    }
}
