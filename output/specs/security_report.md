# Informe de seguridad: Registro y activación del MVP de wallet

**Feature:** Historia «Registro y activación del MVP de wallet» (Épica #1; tareas #12 BFF, #13 UI, #14 Analítica)
**Fuente:** `packages/user-stories/user-onboarding-mvp-completion.sp.md`
**Fecha:** 2026-11-06
**Rol:** Revisor de seguridad de aplicaciones (AppSec)

> **Declaración de evidencia.** Esta es una revisión documental y de diseño. No ejecuté comandos, pruebas ni herramientas, y no tuve acceso al repositorio. Los comandos de verificación son **propuestas, no resultados**.
>
> La evidencia disponible es limitada:
> - **Arquitectura** llega truncada después de `E1 … 201 Created`.
> - **Backend** solo implementó E1 (`POST /onboarding/sessions`), y su repositorio está truncado.
> - **Frontend** es solo especificación, truncada en la sección de seguridad.
> - **Datos** no entregó nada.
> - **Testing** no ejecutó nada.
>
> Solo reporto riesgos que se sustentan en ese material. Donde la evidencia no alcanza, lo digo como «no verificable», no como vulnerabilidad confirmada.

---

## 1. Resumen

### 1.1 Activos y datos sensibles

| Activo / dato | Dónde aparece | Sensibilidad |
|---|---|---|
| Datos personales y documento de identidad (KYC) | Pasos `PERSONAL_DATA`, `IDENTITY_DOCUMENT`; `POST /kyc/verifications` | Alta. Campos y requisitos aún por definir con Cumplimiento y Producto. |
| Estado de la wallet (`PENDING` → `ACTIVE`) | `walletStatus`, `WalletActivationPort` | Alta. Habilita pagos. |
| Sesión de onboarding (`sessionId`, `userId`, `version`, `status`) | Tabla `onboarding_session` (supuesta) | Media. |
| Token de autenticación `Authorization: Bearer` | Todas las llamadas | Alta. El mecanismo real no está confirmado. |
| Eventos analíticos con `userId` y timestamps | `onboarding_started` y los eventos pendientes | Media. |
| Gasto de adquisición, CAC, MAU | Reporte mensual (#14) | Media (información financiera interna). |
| `acquisitionChannel` | Body de E1, controlado por el cliente | Baja, pero alimenta la atribución de CAC. |

### 1.2 Puntos de entrada

| Punto de entrada | Estado | Observación |
|---|---|---|
| E1 `POST /onboarding/sessions` | Implementado (Backend), **NO EJECUTADO** | Único endpoint revisable con código. |
| Consulta de progreso, envío de paso, completar/activar | **No definidos ni implementados** | Es el área de mayor riesgo y no se puede revisar. |
| `POST /kyc/verifications` | Existe según el Issue, contrato no documentado | Integración externa o interna sin contrato. |
| Reporte mensual MAU/CAC | No definido | Requiere control de acceso. |
| Formulario de onboarding (UI) | Spec solamente | Campos de KYC sin definir (SP-4). |
| Fuente de gasto de adquisición (`AcquisitionSpendPort`) | Sin definir | Integración con datos financieros. |

### 1.3 Conclusión

- **Hallazgos confirmados con evidencia:** ninguno de severidad Crítica.
- **Riesgos de diseño sin cobertura (3 Altos):**
  - La activación de la wallet no está condicionada explícitamente al resultado de KYC.
  - Los endpoints pendientes no definen control de acceso a nivel de objeto.
  - El tratamiento de datos KYC no está definido.
- **Medios:** autenticación no verificable, límites de uso, `acquisitionChannel`, `Idempotency-Key`, unicidad de sesión, PII en logs y eventos, y acceso al reporte.
- **Buenas prácticas observadas en lo implementado (E1 y spec de UI):**
  - `userId` tomado del token.
  - Consultas parametrizadas.
  - Errores genéricos sin trazas.
  - `X-Correlation-Id` sanitizado.
  - Navegación de la UI con rutas locales.
  - Sin `innerHTML` ni almacenamiento de tokens.
  - Timestamps asignados por el servidor.

---

## 2. Hallazgos (ordenados por severidad)

| ID | Severidad | Componente | Descripción | Impacto | Corrección propuesta |
|---|---|---|---|---|---|
| **SEC-01** | **Alta** | Criterio 3; `CompleteOnboardingUseCase`, `WalletActivationPort` (diseño, no implementado) | El diseño dice que al «completar todos los pasos requeridos» se solicita la activación. No exige que el resultado de `POST /kyc/verifications` sea **aprobado y persistido en servidor**. Si «paso completado» equivale a «datos enviados», la activación podría ocurrir con KYC rechazado, pendiente o no ejecutado. | Activación de wallets sin identidad verificada: fraude, lavado de activos, incumplimiento regulatorio (A04 Diseño inseguro, A01 Control de acceso). | La regla de dominio debe exigir `kycResult == APPROVED` leído del servidor, nunca del cliente. `walletStatus`, `status` y `version` no deben aceptarse en ningún body. Añadir al criterio 3: «la wallet solo se activa si la verificación KYC fue aprobada». Ver SEC-01 en §4. |
| **SEC-02** | **Alta** | Endpoints pendientes (progreso, envío de paso, completar) | Los contratos no existen. Al incorporar `sessionId` en ruta o body existe riesgo de acceso a objetos de otro usuario (IDOR/BOLA). E1 evita el problema porque no recibe `sessionId`. | Lectura o modificación del progreso y datos KYC de otros usuarios; activación de la wallet de un tercero (A01). | Toda operación sobre una sesión resuelve `session.userId == token.userId`. Si no coincide, responder 404 (sin distinguir de «no existe»). Incluirlo en el contrato antes de implementar. Ver §4. |
| **SEC-03** | **Alta** | Datos KYC/PII (pasos `PERSONAL_DATA`, `IDENTITY_DOCUMENT`; persistencia; eventos) | Los requisitos de KYC y documentación están sin definir. No hay requisitos sobre qué se persiste en el BFF, cifrado en reposo, retención ni consentimiento. La spec de Backend deja la tabla como «supuesto» y no recibió el modelo de Datos. | Exposición de documentos de identidad; incumplimiento de protección de datos personales (a confirmar con Cumplimiento/Legal la normativa peruana aplicable) (A02 Fallas criptográficas, A04). | El BFF no persiste el documento ni los datos KYC. Los reenvía al servicio KYC y guarda solo referencia y resultado. Definir con Cumplimiento: base legal, consentimiento, retención, cifrado en reposo y en tránsito. Añadir estos puntos a los criterios. Ver §4. |
| **SEC-04** | Media | Autenticación (E1 y siguientes) | El mecanismo es una **suposición** («se asume token Bearer»; H-07). No se ve cómo `OnboardingResource` valida el token (firma, expiración, emisor, audiencia). No hay evidencia de un defecto, pero tampoco de que esté correcto. | Si el token se decodifica sin verificar firma o vigencia, es posible suplantar a cualquier usuario. Este riesgo se elevaría a **Crítico** si se confirma. (A07) | Validar el token con la librería estándar del proveedor de identidad (firma, `exp`, `iss`, `aud`). Rechazar `alg: none`. Confirmar el mecanismo real con Arquitectura. Ver §4. |
| **SEC-05** | Media | E1 y el envío de paso (que invoca KYC) | Ningún artefacto menciona límites de tasa, cuotas por usuario o bloqueo ante abuso. E1 crea sesiones y el envío de paso dispara llamadas a KYC, potencialmente costosas. | Abuso del servicio KYC (costo y degradación), enumeración o fuerza bruta de datos de documento, DoS de aplicación (A04, A07). | Rate limit por usuario e IP en E1 y en el envío de paso, con 429 y `Retry-After`. Límite de intentos de verificación por sesión. Añadirlo como criterio de aceptación. Ver §4. |
| **SEC-06** | Media | `acquisitionChannel` (E1, `StartOnboardingRequest`), eventos, reporte #14 | Solo se valida `@Size(max=50)`. El valor lo controla el cliente, es texto libre y alimenta la atribución de CAC y los reportes. Sin lista cerrada o patrón: el cliente puede falsear la atribución, y si el reporte se renderiza o exporta (CSV/HTML) sin escapar hay riesgo de XSS almacenado o de inyección de fórmulas en CSV. | Manipulación de MAU/CAC; XSS o CSV injection en la herramienta de reporte (A03, A04). | Aceptar solo valores de una lista cerrada, o un patrón estricto (`^[a-z0-9_-]{1,50}$`). Normalizar y rechazar el resto con 400. Escapar al renderizar y prefijar celdas CSV que comiencen con `= + - @`. El gasto de adquisición debe venir de una fuente de servidor. Ver §4. |
| **SEC-07** | Media | `Idempotency-Key` (todos los endpoints que mutan) | La semántica no está definida (H-05): ventana de validez, qué se reproduce y cómo se acota. Si la clave se almacena o consulta solo por valor, sin `userId` y ruta, un usuario podría recibir la respuesta de otro. | Fuga de datos entre usuarios; reproducción de operaciones (A01, A04). | Acotar la clave por `(userId, método, ruta)`, con expiración y comparación del hash del body. Misma clave con body distinto → 422/409. Ver §4. |
| **SEC-08** | Media | `OnboardingSessionRepository`, tabla `onboarding_session` (supuesta) | La unicidad de sesión `IN_PROGRESS` por usuario depende de un índice único parcial «esperado», **no confirmado** por Datos. Sin él, concurrencia produce sesiones duplicadas. | Eventos `onboarding_started` duplicados que inflan MAU, distorsionan la mediana de duración y el CAC (integridad de datos, A04, A08). | Datos debe crear y confirmar `UNIQUE (user_id) WHERE status = 'IN_PROGRESS'`. Probar con peticiones concurrentes. Ver §4. |
| **SEC-09** | Media | Logs y eventos (`OnboardingSessionRepository` con `Logger`; `OnboardingEvent`; eventos de fin de paso y cierre) | El contenido de los logs del repositorio no es visible (truncado) y el payload de los eventos no pendientes no está definido. Con KYC en juego existe riesgo de que se registren documentos, nombres o tokens. No hay requisito explícito de qué no debe registrarse. | Exposición de PII en logs y en el pipeline analítico (A09 Fallas de logging, A02). | Los eventos llevan solo IDs, `stepId`, resultado y timestamp. Prohibir PII, tokens y valores de campos en logs. Ver §4. |
| **SEC-10** | Media | Reporte mensual (#14), `GetOnboardingReportUseCase` | Expone gasto de adquisición, CAC y volumen de usuarios. Ningún artefacto define quién puede consultarlo. | Acceso no autorizado a información financiera y de negocio (A01). | Restringir a un rol específico (p. ej. `analytics:read`) con 403 para el resto. Devolver solo agregados, sin `userId`. Ver §4. |
| **SEC-11** | Baja | Manejo global de errores (Backend) | Solo hay mappers para `ValidationException`, `ConstraintViolationException` y `StorageException`. No se lista uno genérico. Una excepción no prevista dependería del comportamiento por defecto del framework. | Posible respuesta con detalles internos, según la configuración del entorno (A05). | Añadir un mapper de `Throwable` que responda 500 genérico con `correlationId`. Verificar con perfil de producción. Ver §4. |
| **SEC-12** | Baja | `OnboardingValidationException.getFieldErrors()` | Devuelve el mapa interno mutable, aunque el constructor sí copia defensivamente (H-10). | Impacto de seguridad mínimo: el mapa solo contiene mensajes de validación. Es higiene de código. | Devolver `Collections.unmodifiableMap(...)` o `Map.copyOf(...)`. Ver §4. |
| **SEC-13** | Baja | Dependencias y secretos (todo el proyecto) | No se vieron `pom.xml`, `build.gradle` ni `package.json` y no se pudo evaluar si hay dependencias vulnerables. Tampoco se ven credenciales (conexión a BD, cliente KYC, fuente de gasto). | **No evaluado.** Cualquier conclusión sería inventada (A06, A02). | Ejecutar análisis de composición de software y detección de secretos en CI. Credenciales solo desde un gestor de secretos o variables de entorno. Ver §4. |

---

## 3. Verificación de los criterios de aceptación

### 3.1 Riesgos introducidos por los criterios actuales

| Criterio | Evaluación de seguridad |
|---|---|
| 1. Secuencia y paso actual visibles | Sin riesgo directo. El diseño usa el servidor como fuente de verdad, lo cual es correcto. |
| 2. Bloqueo ante datos faltantes o inválidos | La validación debe ser **del servidor**. La del cliente es solo UX (la spec de UI lo reconoce). Los mensajes accionables no deben revelar si un documento ya existe o está registrado (enumeración). Ver SEC-05. |
| 3. Wallet activa al finalizar | **Riesgo:** no menciona la dependencia del resultado de KYC. Ver SEC-01. |
| 4. Registro de eventos | **Riesgo:** no restringe el contenido (PII, ver SEC-09) ni exige que los eventos solo se generen tras validación del servidor. |
| 5. Mediana de 8 a 3 minutos | Sin riesgo directo, pero la presión por reducir tiempo no debe degradar los controles de KYC. La frontera de eventos sigue abierta. |
| 6. MAU y CAC | La atribución y el gasto deben provenir de fuentes de servidor, no del cliente. Ver SEC-06 y SEC-10. |

### 3.2 Criterios de seguridad faltantes (propuestos)

1. La wallet solo pasa a `ACTIVE` si el servidor confirma `KYC=APPROVED`. Ningún campo del cliente puede fijar `status` ni `walletStatus`.
2. Toda operación sobre una sesión valida que pertenece al usuario autenticado.
3. Hay límites de tasa por usuario e IP en E1 y en el envío de paso.
4. Los eventos y logs no contienen PII, documentos ni tokens.
5. Los datos KYC no se persisten en el BFF más allá de lo definido con Cumplimiento (retención y consentimiento documentados).
6. El reporte MAU/CAC es accesible solo para el rol autorizado y devuelve solo agregados.
7. La comunicación es solo por TLS. CORS y cabeceras de seguridad definidos para el BFF y la UI (hoy no especificados en ningún artefacto).

---

## 4. Verificación de cada corrección

Las URLs y variables (`$BASE`, `$TOKEN`) son marcadores de posición. Los endpoints futuros no tienen ruta definida, por eso se usa `<endpoint>`. **Nada de esto se ejecutó.**

**SEC-01: activación condicionada a KYC.**
- Prueba de integración con KYC simulado:
  - Resultado `REJECTED` o `PENDING` → completar devuelve 409/422 y la wallet sigue `PENDING`.
  - Resultado `APPROVED` → `ACTIVE`.
- Enviar `{"walletStatus":"ACTIVE","status":"COMPLETED"}` en cualquier body → se ignora o devuelve 400, y la wallet no cambia.

**SEC-02: propiedad de la sesión.**
```bash
# Con el token del usuario B sobre la sesión del usuario A
curl -i -H "Authorization: Bearer $TOKEN_B" $BASE/<endpoint>/$SESSION_ID_DE_A
# Esperado: 404 y ningún dato de A
```
Repetir para consulta, envío de paso y completar.

**SEC-03: datos KYC.**
- Enviar un documento con valor centinela (p. ej. `99999999`) y comprobar que no aparece en la BD del BFF: `SELECT * FROM onboarding_session` y búsqueda del valor en todas las columnas y tablas del BFF.
- Revisión documentada de Cumplimiento sobre retención y consentimiento. Es un entregable, no un comando.

**SEC-04: validación del token.** Todas estas peticiones deben devolver 401:
```bash
for T in "" "invalido" "$TOKEN_EXPIRADO" "$TOKEN_FIRMA_ALTERADA" "$TOKEN_ALG_NONE" "$TOKEN_OTRA_AUDIENCIA"; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/onboarding/sessions \
    -H "Authorization: Bearer $T" -H "Idempotency-Key: $(uuidgen)"
done
```

**SEC-05: límites de tasa.**
```bash
for i in $(seq 1 100); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/onboarding/sessions \
    -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)"
done | sort | uniq -c
# Esperado: aparecen 429 al superar el umbral configurado
```

**SEC-06: `acquisitionChannel`.** Todos deben devolver 400 con `fieldErrors`:
```bash
for V in '<script>alert(1)</script>' '=HYPERLINK("http://x")' "'; DROP TABLE x;--" 'canal desconocido'; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/onboarding/sessions \
    -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
    -H "Content-Type: application/json" -d "{\"acquisitionChannel\":\"$V\"}"
done
```
Para el reporte, probar con un valor que empiece por `=` y comprobar que la exportación CSV lo neutraliza.

**SEC-07: `Idempotency-Key`.**
- Mismo UUID con `$TOKEN_A` y `$TOKEN_B` → los `sessionId` son distintos y no hay datos cruzados.
- Misma clave con body distinto → 409/422.
- Repetir pasada la ventana de validez definida → se procesa como nueva solicitud.

**SEC-08: unicidad de sesión.**
```bash
seq 1 20 | xargs -P 20 -I{} curl -s -X POST $BASE/onboarding/sessions \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" | sort | uniq -c
# Esperado: un único sessionId
```
Verificar también en BD:
```sql
SELECT count(*) FROM onboarding_session WHERE user_id = '<uuid>' AND status = 'IN_PROGRESS';  -- debe ser 1
\d onboarding_session  -- debe existir el índice único parcial
```
Debe emitirse un solo `onboarding_started`.

**SEC-09: PII en logs y eventos.**
- Ejecutar un flujo con datos centinela y buscarlos en logs y eventos: `grep -ri "99999999" logs/ eventos/` → sin coincidencias.
- Esquema del evento revisado: solo `userId`, `sessionId`, `stepId`, resultado, `timestamp`.

**SEC-10: acceso al reporte.**
```bash
curl -i -H "Authorization: Bearer $TOKEN_USUARIO_COMUN" $BASE/<endpoint-reporte>   # 403
curl -i -H "Authorization: Bearer $TOKEN_ANALITICA"       $BASE/<endpoint-reporte>   # 200, solo agregados
```

**SEC-11: errores genéricos.**
```bash
curl -i -X POST $BASE/onboarding/sessions -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: text/plain" -d 'x'
```
Con el perfil de producción, la respuesta debe ser un `ErrorResponse` sin traza, SQL ni nombres de clase, y con `correlationId`.

**SEC-12: mapa inmutable.** Prueba unitaria: `assertThrows(UnsupportedOperationException.class, () -> ex.getFieldErrors().put("x","y"));`

**SEC-13: dependencias y secretos** (comandos típicos; ajustar al gestor de build real):
```bash
mvn org.owasp:dependency-check-maven:check     # backend
npm audit --omit=dev                            # frontend
gitleaks detect --source .                      # secretos en el repositorio
```
Sin vulnerabilidades altas o críticas sin tratar y sin secretos detectados.

---

## 5. Áreas revisadas sin hallazgos (con la evidencia disponible)

| Área | Resultado |
|---|---|
| Inyección SQL en E1 | Sin hallazgos. Backend declara consultas parametrizadas con `PreparedStatement`. El repositorio está truncado, por lo que debe confirmarse en revisión de código. |
| `userId` desde el cliente | Sin hallazgos. Se toma del token, no del body. |
| Mass assignment en E1 | Sin hallazgos. El DTO solo acepta `acquisitionChannel`. Debe mantenerse en los endpoints futuros (ver SEC-01). |
| Inyección en logs vía `X-Correlation-Id` | Sin hallazgos. Backend declara que el valor se sanea y que se genera uno nuevo si es inválido. |
| XSS en la UI | Sin hallazgos. Spec: sin `innerHTML`, todo se interpola como texto y los mensajes de campo se recortan. Pendiente revisar el código real. |
| Redirección abierta en la UI | Sin hallazgos. La navegación usa una tabla local y nunca un `target` del servidor. `nextAction.target` es una clave lógica. |
| Almacenamiento de secretos en el cliente | Sin hallazgos. La UI no guarda tokens ni usa `localStorage`/`sessionStorage`. El interceptor de autenticación existente no se revisó. |
| Fuga de detalles internos en errores de E1 | Sin hallazgos en lo descrito. Respuestas 400/401/500 sin trazas ni SQL (ver SEC-11 para casos no cubiertos). |
| Confianza en el reloj del cliente | Sin hallazgos. Los timestamps los asigna el servidor. |
| Control de concurrencia | Sin hallazgos. El campo `version` y el manejo de 409 están previstos. |
| Dependencias y secretos | **No evaluable** (SEC-13). |

---

## 6. Recomendaciones (priorizadas)

**Antes de implementar los endpoints pendientes (bloqueantes):**
1. **SEC-01.** Definir en el contrato que la activación depende de `KYC=APPROVED` en el servidor. Prohibir que `status` o `walletStatus` entren por el cliente.
2. **SEC-02.** Incluir en el contrato de cada endpoint con `sessionId` la verificación de propiedad y la respuesta 404.
3. **SEC-03.** Cerrar con Cumplimiento y Producto los requisitos de KYC (campos, retención, consentimiento, cifrado). Decidir que el BFF no persiste el documento.
4. **SEC-04.** Confirmar el mecanismo real de autenticación y documentar cómo se valida.

**Antes del lanzamiento:**
5. **SEC-05.** Implementar límites de tasa y de intentos de verificación.
6. **SEC-06 y SEC-10.** Lista cerrada de canales, atribución desde el servidor, escape de salida, control de acceso del reporte.
7. **SEC-07 y SEC-08.** Definir la semántica de `Idempotency-Key` y confirmar con Datos el índice único parcial.
8. **SEC-09.** Esquema de eventos sin PII y política de logging acordada.

**Mejoras continuas:**
9. **SEC-11 a SEC-13.** Mapper genérico, mapa inmutable y SCA/detección de secretos en CI.
10. Definir cabeceras de seguridad, CORS y TLS obligatorio (hoy no especificados). Añadir pruebas negativas de seguridad al plan de Testing, que actualmente no las incluye para los endpoints bloqueados (BL-01 a BL-04).

---

## 7. Checklist de verificación

**Control de acceso y autenticación**
- [ ] Token validado con firma, `exp`, `iss`, `aud`; `alg: none` rechazado (SEC-04).
- [ ] `userId` solo desde el token en todos los endpoints.
- [ ] Todo acceso a una sesión comprueba propiedad; acceso ajeno → 404 (SEC-02).
- [ ] Reporte MAU/CAC restringido a rol de analítica y solo con agregados (SEC-10).

**Lógica de negocio**
- [ ] Wallet `ACTIVE` solo con KYC aprobado confirmado por el servidor (SEC-01).
- [ ] Ningún body acepta `status`, `walletStatus` ni `version` como entrada de autoridad.
- [ ] Eventos de fin de paso y cierre solo se emiten tras validación del servidor.

**Entradas e inyección**
- [ ] `acquisitionChannel` con lista cerrada o patrón estricto (SEC-06).
- [ ] Consultas parametrizadas confirmadas en el código completo del repositorio.
- [ ] Salidas de reporte escapadas (HTML y CSV).

**Abuso y concurrencia**
- [ ] Límite de tasa con 429 en E1 y en el envío de paso (SEC-05).
- [ ] Índice único parcial de sesión confirmado y probado con concurrencia (SEC-08).
- [ ] `Idempotency-Key` acotada por usuario y ruta, con ventana definida (SEC-07).

**Datos sensibles**
- [ ] El BFF no persiste documentos ni datos KYC (SEC-03).
- [ ] Logs y eventos sin PII ni tokens, verificado con datos centinela (SEC-09).
- [ ] Retención y consentimiento aprobados por Cumplimiento.

**Errores y configuración**
- [ ] Mapper genérico de `Throwable` con 500 sin detalles (SEC-11).
- [ ] TLS obligatorio, CORS restringido y cabeceras de seguridad definidos.
- [ ] Secretos solo desde gestor de secretos o variables de entorno (SEC-13).

**Dependencias**
- [ ] SCA backend y frontend sin vulnerabilidades altas o críticas pendientes (SEC-13).
- [ ] Detección de secretos en CI sin hallazgos.

**Pendiente de otros roles**
- [ ] Contrato completo de Arquitectura (después de `E1 … 201`), incluido `POST /kyc/verifications`.
- [ ] Modelo de Datos confirmado para `onboarding_session`.
- [ ] Pruebas negativas de seguridad ejecutadas, con resultados reales, para SEC-01 a SEC-12.