package br.com.suaempresa.sambamanager.service;

public final class AccessCheckDiagnosticsSmokeTest {
    public static void main(String[] args) {
        String conflict = "SAMBA_MANAGER_ERROR:0 - Faturamento | Multiple connections to a server or shared resource "
                + "by the same user, using more than one user name, are not allowed.\n"
                + "SAMBA_MANAGER_ERROR:1 - Contabilidade | Multiple connections to a server or shared resource "
                + "by the same user, using more than one user name, are not allowed.\n";
        if (!AccessCheckDiagnostics.diagnosis(conflict, "192.168.0.93").contains("outro usuário")) {
            throw new AssertionError("Windows credential conflict was not recognized");
        }
        if (!AccessCheckDiagnostics.summary(conflict).contains("conflito de credenciais Windows=2")) {
            throw new AssertionError("Repeated failures were not summarized");
        }
        if (AccessCheckDiagnostics.diagnosis("SAMBA_MANAGER_ACCESS:Zm9v", "192.168.0.93") != null) {
            throw new AssertionError("Successful response diagnosed as error");
        }
    }
}
