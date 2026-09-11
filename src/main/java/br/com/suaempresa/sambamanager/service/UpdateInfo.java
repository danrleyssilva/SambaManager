package br.com.suaempresa.sambamanager.service;

public record UpdateInfo(String version, String downloadUrl, String sha256, boolean required, String notes) {
}
