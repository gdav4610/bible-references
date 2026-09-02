# Wiki de bible-references

Notas transversales: decisiones de diseño, criterios y trampas que conviene no volver a
descubrir. A diferencia de los runbooks de `docs/backups`, que cuentan **una** intervención con su
procedimiento y su reversión, estas páginas pretenden servir la próxima vez que aparezca un
problema del mismo tipo.

## Índice

| Página | De qué trata |
|---|---|
| [`diseno-en-capas-y-clean-architecture.md`](diseno-en-capas-y-clean-architecture.md) | Cuánta ceremonia arquitectónica compensa a esta escala, y qué principios sí valen |
| [`cache-en-la-capa-de-servicio.md`](cache-en-la-capa-de-servicio.md) | Dónde poner la caché, cómo construir la clave, y por qué el orden de los aspectos importa |
| [`latencia-en-el-camino-de-lectura.md`](latencia-en-el-camino-de-lectura.md) | Trabajo síncrono que el cliente no pidió, y por qué en Lambda no vale hacerlo asíncrono |
| [`indices-de-expresion-en-postgres.md`](indices-de-expresion-en-postgres.md) | Indexar `unaccent`, acoplamiento entre consulta e índice, y el orden de despliegue |
| [`migracion-lambda.md`](migracion-lambda.md) | La migración de ECS a Lambda, narrada |
| [`rate-limiting-lambda.md`](rate-limiting-lambda.md) | Las cuatro capas de limitación de tasa |

## Cómo alimentarla

Una página por tema, autosuficiente, con la fecha del estado que describe. Lo que la hace
útil no es la regla general —esas están en cualquier libro— sino **la evidencia concreta de
este proyecto**: el identificador del recurso, la cifra medida, el error exacto, la fecha. Una
página que no se pueda contrastar con algo del repositorio o de la cuenta de AWS sobra.

Cuando una entrada quede desmentida por un cambio posterior, corregirla en su sitio y anotar
qué la desmintió; no borrar el error, que suele ser lo más instructivo.
