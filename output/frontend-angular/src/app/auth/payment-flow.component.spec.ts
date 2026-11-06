Si el proyecto usa Jest, el spec solo utiliza `describe/it/expect` y utilidades de Angular. Ajustar solo el comando (por ejemplo `npx jest payment-flow.component.spec.ts`). **Pendiente de confirmar el runner.**

### 7.4 Persistencia (BLOQUEADO)

No hay comandos: faltan el DDL y los nombres reales. Cuando Datos los entregue, ejecutar P-01..P-06 contra una base efímera.

### 7.5 Limpieza

1. **Datos creados por A-xx.** Los pagos de prueba quedan en la base del entorno. Limpiar solo con credenciales de prueba y filtrando por los pagadores A y B de prueba, con una sentencia del tipo `DELETE FROM <tabla_pagos> WHERE payer_id IN ('<pagadorA>','<pagadorB>')` (**marcador**: la tabla y la columna reales no se conocen). Preferir una base efímera o contenedor descartable.
2. **Pagos PENDING.** Esperar su expiración o limpiarlos junto con lo anterior. Pueden bloquear nuevas ejecuciones del mismo pagador (regla de un PENDING por pagador).
3. **Eventos publicados.** Si el entorno publica eventos reales de activación, usar un entorno aislado. Los eventos emitidos por pruebas pueden contaminar la métrica de MAU.
4. **Procesos.** Detener el BFF y el mock levantados para la prueba.
5. **Secretos.** `unset TOKEN_A TOKEN_B` y no guardar tokens en el historial del shell ni en el repositorio.
6. **Frontend.** No genera estado persistente (usa `HttpTestingController`).

---

## 8. Código de las pruebas

> **Ningún bloque fue compilado ni ejecutado.** Los bloques marcados "firma SUPUESTA" o "campos SUPUESTOS" requieren ajuste puntual indicado en el propio código.

### 8.1 Dominio: `PaymentStatusTest`