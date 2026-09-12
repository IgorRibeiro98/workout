package com.example.service

/**
 * Dublê de [FcmTokenProvider] para testes.
 *
 * Vivia em `src/main` (auditoria 2026-09-12), ou seja, era empacotado no APK sem nenhuma
 * referência no app. Dublê de teste é código de teste.
 */
class FakeFcmTokenProvider(var token: String? = null) : FcmTokenProvider {
    override suspend fun getToken(): String? = token
}
