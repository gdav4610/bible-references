# Requisitos no funcionales — plantilla

Machote para recabar los requisitos no funcionales de un servicio. Se rellena **antes** de
diseñar, y se revisa cada vez que cambie la arquitectura o el perfil de uso.

Los ejemplos marcados *(ej.)* vienen de `bible-references` y están para dar escala, no para
copiarse: bórralos al instanciar la plantilla.

---

## Cómo se usa

**Una fila sin forma de comprobarla es un deseo, no un requisito.** Por eso todas las tablas
llevan la columna *Cómo se verifica*: si no se puede escribir, o el número está de adorno o
falta instrumentación, y ambas cosas conviene saberlas ahora y no en producción.

Tres reglas al rellenarla:

1. **Percentiles, nunca promedios.** El promedio esconde justo lo que duele. Si solo se va a
   seguir un número, que sea el p95.
2. **Decir siempre dónde se mide y bajo qué carga.** «200 ms» no significa nada sin «en el
   borde, con 5 rps sostenidos».
3. **Distinguir objetivo de límite.** El objetivo guía el diseño; el límite es lo que, al
   incumplirse, obliga a actuar. No tienen por qué coincidir.

### Antipatrones frecuentes

| Redacción | Problema |
|---|---|
| «El sistema debe ser rápido» | No es verificable |
| «Latencia media < 300 ms» | El promedio oculta la cola; usar percentiles |
| «Disponibilidad 99,99 %» | Sin ventana de servicio ni exclusiones, no se puede calcular |
| «Debe ser seguro» | Sin modelo de amenaza no dice nada |
| «Debe escalar» | ¿Hasta cuánto, en cuánto tiempo, a qué coste? |

---

## 1. Identificación

| Campo | Valor |
|---|---|
| Servicio | `<nombre>` |
| Responsable | `<persona o equipo>` |
| Fecha de la versión | `<AAAA-MM-DD>` |
| Estado | `borrador` / `acordado` / `en revisión` |
| Documentos relacionados | `<arquitectura, runbooks, contratos de API>` |

### Alcance

Qué entra y, sobre todo, **qué queda fuera**:

- Entra: `<…>`
- No entra: `<…>`

### Perfil de uso

Los números de abajo no significan nada sin esto.

| Campo | Valor |
|---|---|
| Usuarios concurrentes esperados | `<…>` *(ej. decenas, no miles)* |
| Peticiones al día | `<…>` |
| Franja de mayor uso | `<…>` |
| Estacionalidad | `<…>` |
| Proporción lectura / escritura | `<…>` *(ej. 100 / 0)* |
| Crecimiento previsto a 12 meses | `<…>` |

---

## 2. Convenciones de medida

Fijarlas antes de las tablas evita discusiones estériles después.

| Concepto | Definición acordada |
|---|---|
| Punto de medida | `<en el borde / en el origen / en el cliente>` |
| Ventana de evaluación | `<30 días naturales / mes calendario>` |
| Percentiles seguidos | `p50, p95, p99` |
| Qué cuenta como error | `<5xx; 4xx excluidos salvo 429>` |
| Peticiones excluidas | `<sondas de salud, calentamiento, escáneres bloqueados en el borde>` |
| Fuente de verdad de las métricas | `<CloudWatch, logs de acceso, RUM>` |

---

## 3. Latencia

### 3.1 Objetivos por operación

| Operación | p50 | p95 | p99 | Carga de | Cómo se verifica | Si se incumple |
|---|---|---|---|---|---|---|---|
| `<GET /recurso>` | `<…>` | `<…>` | `<…>` |  `<5 rps>` | `<…>` | `<…>` |
| `<POST /recurso>` | `<…>` | `<…>` | `<…>` | `<5 rps>` | `<…>` | `<…>` |
| *(ej.)* `GET /api/bible/chapter/{l}/{c}` | 150 ms | 500 ms | 1 s | 5 rps | `curl -w %{time_total}` con la cabecera del frontend | Revisar caché de la consulta |

---

## 4. Throughput y capacity

| Métrica | Objetivo | Límite duro | Origen del límite | Cómo se verifica |
|---|---|---|---|---|
| Peticiones por segundo sostenidas | `<…>` | `<…>` | `<…>` | `<…>` |
| Pico admisible | `<…>` | `<…>` | `<…>` | `<…>` |
| Ráfaga instantánea | `<…>` | `<…>` | `<…>` | `<…>` |
| Ejecuciones concurrentes | `<…>` | `<…>` | `<…>` | `<…>` |
| Conexiones a base de datos | `<…>` | `<…>` | `<…>` | `<…>` |
| Tamaño máximo de respuesta | `<…>` | `<…>` | `<…>` | `<…>` |
| Tiempo máximo de una operación | `<…>` | `<…>` | `<…>` | `<…>` |
| *(ej.)* Tamaño de respuesta | < 3 MB | **6 MB** | Invocación síncrona de Lambda | Búsqueda acotada por `LIMIT`, con aviso de truncado |

**Comportamiento al superar la capacidad.** Qué se prefiere, y por qué:

- [ ] Rechazar rápido (429) antes que degradar a todos
- [ ] Encolar hasta `<n>` y luego rechazar
- [ ] Degradar funcionalidad: `<qué se apaga primero>`

---

## 5. Disponibilidad

### 5.1 Ventana de servicio

**Definirla antes que el porcentaje**, o el porcentaje no se puede calcular.

| Campo | Valor |
|---|---|
| Horario comprometido | `<24×7 / L-V 8-20 / …>` |
| Zona horaria | `<…>` |
| Paradas programadas | `<…>` |
| ¿Las paradas programadas cuentan como indisponibilidad? | `<sí / no>` |
| *(ej.)* Ventana real | 07:40–23:10 (CDMX): fuera de ella la base de datos está parada **a propósito**, por coste |

> Si hay apagado programado, decirlo aquí y excluirlo explícitamente. Un servicio con 8,5 h
> diarias de parada deliberada tiene ~64 % de disponibilidad bruta: prometer «99,9 %» sin
> acotar la ventana es prometer algo falso.

### 5.2 Objetivo

| Componente | SLO | Ventana | Definición de «disponible» | Cómo se mide |
|---|---|---|---|---|
| `<API>` | `<…>` | `<…>` | `<…>` | `<…>` |
| `<Frontend>` | `<…>` | `<…>` | `<…>` | `<…>` |
| `<Base de datos>` | `<…>` | `<…>` | `<…>` | `<…>` |

### 5.3 Tolerancia a fallos

| Escenario | Comportamiento esperado | Detección | Recuperación |
|---|---|---|---|
| Caída de una zona de disponibilidad | `<…>` | `<…>` | `<…>` |
| Base de datos no alcanzable | `<…>` | `<…>` | `<…>` |
| Dependencia externa lenta | `<…>` | `<…>` | `<…>` |
| Despliegue defectuoso | `<…>` | `<…>` | `<…>` |

---

## 6. Presupuesto de error

El SLO puesto del revés: cuánto se puede fallar antes de tener que parar y arreglar.

| SLO mensual | Presupuesto (30 días) |
|---|---|
| 99 % | 7 h 12 min |
| 99,5 % | 3 h 36 min |
| 99,9 % | 43 min 12 s |
| 99,95 % | 21 min 36 s |
| 99,99 % | 4 min 19 s |

| Campo | Valor |
|---|---|
| SLO elegido | `<…>` |
| Presupuesto resultante | `<…>` |
| Ventana | `<…>` |
| Sobre qué tráfico se calcula | `<…>` |

**Política de consumo** — esto es lo que le da sentido al número:

| Consumo | Acción |
|---|---|
| < 50 % | Normal: se despliegan funcionalidades nuevas |
| 50–75 % | Revisar causas antes del siguiente despliegue grande |
| 75–100 % | Solo correcciones y trabajo de fiabilidad |
| Agotado | Congelación: nada que no reduzca riesgo, hasta la siguiente ventana |

| Campo | Valor |
|---|---|
| Ritmo de quemado que dispara alerta | `<…>` |
| Quién decide la congelación | `<…>` |
| Qué queda exento | `<parches de seguridad, …>` |

---

## 7. Consistencia de datos

| Dato | Modelo | Ventana de convergencia | Quién puede leer desfasado | Cómo se verifica |
|---|---|---|---|---|
| `<…>` | `<fuerte / eventual>` | `<…>` | `<…>` | `<…>` |
| *(ej.)* Texto y keywords | Eventual | Hasta 24 h | Cualquier lector: es dato de referencia que no cambia | TTL de caché + invalidación del CDN |

### Cachés declaradas

Cada caché es una fuente de desfase; conviene tenerlas todas en una tabla.

| Caché | Ámbito | TTL | Clave | Cómo se invalida |
|---|---|---|---|---|
| `<CDN>` | `<…>` | `<…>` | `<…>` | `<…>` |
| `<en proceso>` | `<…>` | `<…>` | `<…>` | `<…>` |
| `<navegador>` | `<…>` | `<…>` | `<…>` | `<…>` |

> **La clave tiene que distinguir todo lo que distingue la respuesta.** Si un parámetro afecta
> al resultado y no está en la clave, hay colisión: dos peticiones distintas reciben la misma
> respuesta. Ver `wiki/cache-en-la-capa-de-servicio.md`.

### Transacciones e idempotencia

| Campo | Valor |
|---|---|
| Operaciones que deben ser atómicas | `<…>` |
| Operaciones idempotentes | `<…>` |
| Estrategia ante reintentos | `<…>` |
| Concurrencia (optimista / pesimista) | `<…>` |

---

## 8. Durabilidad y recuperación

| Campo | Objetivo | Real | Cómo se verifica |
|---|---|---|---|
| RPO — pérdida máxima aceptable | `<…>` | `<…>` | `<…>` |
| RTO — tiempo máximo de restauración | `<…>` | `<…>` | `<…>` |
| Retención de copias | `<…>` | `<…>` | `<…>` |
| Protección contra borrado | `<…>` | `<…>` | `<…>` |
| **Última restauración probada** | `<fecha>` | | `<…>` |

> Una copia que nunca se ha restaurado no es una copia, es una suposición. Fijar aquí cada
> cuánto se prueba.

---

## 9. Seguridad

### 9.1 Autenticación y autorización

| Campo | Valor |
|---|---|
| Autenticación de usuarios | `<…>` |
| Autenticación entre servicios | `<…>` |
| Modelo de autorización | `<…>` |
| Gestión de sesiones | `<…>` |

### 9.2 Datos

| Categoría | ¿Se trata? | Dónde vive | Cifrado en reposo | Cifrado en tránsito | Retención |
|---|---|---|---|---|---|
| Datos personales | `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |
| Credenciales | `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |
| Datos de negocio | `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |
| Registros con IP o identificadores | `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |

### 9.3 Superficie expuesta

| Punto de entrada | ¿Público? | Protección | Qué pasa si se salta el borde |
|---|---|---|---|
| `<CDN>` | `<…>` | `<…>` | — |
| `<Puerta de enlace>` | `<…>` | `<…>` | `<…>` |
| `<Balanceador / origen>` | `<…>` | `<…>` | `<…>` |
| `<Base de datos>` | `<…>` | `<…>` | `<…>` |

> **La pregunta que suele faltar:** ¿el origen es alcanzable sin pasar por el borde? Si lo es,
> todo lo que aporta el borde —WAF, limitación de tasa, caché— se esquiva conociendo un DNS o
> una URL. Hacen falta dos capas: red (restringir el ingreso) e identidad del origen (una
> cabecera secreta que el borde inyecta y el origen exige).

### 9.4 Limitación de tasa

| Capa | Ámbito | Límite | Respuesta al superarlo | Coste de una petición rechazada |
|---|---|---|---|---|
| `<borde>` | `<por IP>` | `<…>` | `<…>` | `<…>` |
| `<puerta de enlace>` | `<global>` | `<…>` | `<…>` | `<…>` |
| `<aplicación>` | `<…>` | `<…>` | `<…>` | `<…>` |

> La última columna importa más de lo que parece: un rechazo que ocurre **después** de invocar
> el cómputo se factura igual que una petición legítima.

### 9.5 Secretos

| Secreto | Dónde vive | Rotación | Última rotación | Quién tiene acceso |
|---|---|---|---|---|
| `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |

### 9.6 Cumplimiento

| Requisito | Aplica | Evidencia |
|---|---|---|
| `<…>` | `<sí/no>` | `<…>` |

---

## 10. Observabilidad

| Señal | Existe | Retención | Permite atribución | Cómo se consulta |
|---|---|---|---|---|
| Logs de aplicación | `<…>` | `<…>` | `<…>` | `<…>` |
| Logs de acceso del borde | `<…>` | `<…>` | `<…>` | `<…>` |
| Métricas de latencia | `<…>` | `<…>` | — | `<…>` |
| Métricas de error | `<…>` | `<…>` | — | `<…>` |
| Trazas distribuidas | `<…>` | `<…>` | `<…>` | `<…>` |
| Alarmas | `<…>` | — | — | `<…>` |

**Sin logs de acceso no hay atribución.** Si el plan del proveedor no permite activarlos,
escribirlo aquí: significa que ante un pico de tráfico no se podrá saber de quién viene, y eso
condiciona toda la respuesta a incidentes.

| Campo | Valor |
|---|---|
| Alarmas con destinatario **confirmado** | `<…>` |
| Umbral de cada alarma y su razón | `<…>` |

---

## 11. Restricciones de arquitectura

Lo que **no** se puede elegir. Separado en cuatro clases, porque no se negocian igual.

### 11.1 Impuestas desde fuera

| Restricción | Detalle | Origen | Consecuencia de diseño |
|---|---|---|---|
| Región / residencia de datos | `<…>` | `<…>` | `<…>` |
| Proveedor de nube | `<…>` | `<…>` | `<…>` |
| Plan contratado | `<…>` | `<…>` | `<…>` |
| Presupuesto mensual | `<…>` | `<…>` | `<…>` |
| Licencias | `<…>` | `<…>` | `<…>` |
| Normativa | `<…>` | `<…>` | `<…>` |
| *(ej.)* Servicios de la región | Sin Function URLs ni HTTP API en `mx-central-1` | AWS | Obliga a REST API y al formato de evento v1 |

### 11.2 Límites duros de la plataforma

Los que, al alcanzarse, no dan un aviso: dan un fallo.

| Límite | Valor | Qué ocurre al superarlo | Margen actual |
|---|---|---|---|
| `<tamaño de payload>` | `<…>` | `<…>` | `<…>` |
| `<tiempo máximo de ejecución>` | `<…>` | `<…>` | `<…>` |
| `<concurrencia>` | `<…>` | `<…>` | `<…>` |
| `<conexiones a base de datos>` | `<…>` | `<…>` | `<…>` |
| `<tamaño de artefacto>` | `<…>` | `<…>` | `<…>` |

### 11.3 Decisiones ya tomadas que no se renegocian

Con su motivo, para que dentro de un año se sepa si el motivo sigue vigente.

| Decisión | Motivo | Fecha | ¿Qué la haría reversible? |
|---|---|---|---|
| `<…>` | `<…>` | `<…>` | `<…>` |
| *(ej.)* Apagado nocturno de la base de datos | Coste; no hay usuarios de noche | 2026-08-13 | Usuarios en otra zona horaria |

### 11.4 Restricciones de operación

Las que más se olvidan al diseñar, y las que más condicionan lo que es sostenible.

| Campo | Valor | Consecuencia |
|---|---|---|
| Personas que operan el sistema | `<…>` | `<…>` |
| Horario de guardia | `<…>` | `<…>` |
| Despliegue: automático o manual | `<…>` | `<…>` |
| Vuelta atrás: mecanismo y tiempo | `<…>` | `<…>` |
| Infraestructura como código | `<…>` | `<…>` |
| Migraciones de esquema | `<…>` | `<…>` |

> Una arquitectura que exige más operación de la que hay disponible no es una arquitectura
> ambiciosa: es una que va a fallar de un modo previsible.

### 11.5 Dependencias externas

| Dependencia | Criticidad | SLA declarado | Comportamiento si cae | Alternativa |
|---|---|---|---|---|
| `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |

---

## 12. Compromisos aceptados

Todo NFR se paga con otro. Dejar por escrito qué se sacrificó a conciencia distingue una
decisión de un descuido.

| Se prioriza | Se sacrifica | Motivo | Fecha | Revisar si… |
|---|---|---|---|---|
| `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |
| *(ej.)* Coste | Disponibilidad nocturna | Sin usuarios de noche | 2026-08-13 | Aparecen usuarios en otra franja |

---

## 13. Supuestos y riesgos

| Supuesto | Si resulta falso | Cómo se comprobaría |
|---|---|---|
| `<…>` | `<…>` | `<…>` |

| Riesgo | Probabilidad | Impacto | Mitigación | Dueño |
|---|---|---|---|---|
| `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |

---

## 14. Criterios de aceptación

Un requisito no está acordado hasta que existe la forma de comprobarlo. Esta tabla es el
resumen ejecutable de todo lo anterior.

| # | Requisito | Umbral | Método de verificación | Frecuencia | Estado |
|---|---|---|---|---|---|
| NFR-01 | `<…>` | `<…>` | `<…>` | `<…>` | `<pendiente / cumple / incumple>` |
| NFR-02 | `<…>` | `<…>` | `<…>` | `<…>` | `<…>` |

---

## 15. Registro de cambios

| Fecha | Cambio | Motivo | Quién |
|---|---|---|---|
| `<AAAA-MM-DD>` | `<…>` | `<…>` | `<…>` |
