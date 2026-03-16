package com.distriar.driver

fun formatAddress(order: Order): String {
    val parts = mutableListOf<String>()
    val street = listOfNotNull(order.userCalle?.trim(), order.userNumeracion?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    if (street.isNotEmpty()) parts.add(street)
    order.userBarrio?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
    order.userDepartment?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
    order.userPostalCode?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
    return if (parts.isEmpty()) "Dirección no disponible" else parts.joinToString(" • ")
}

fun orderIsDelivered(order: Order): Boolean {
    val st = order.status?.lowercase()?.trim() ?: ""
    return st == "entregado"
}
