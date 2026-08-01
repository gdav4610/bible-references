# Runbook — Hallazgo 2: el ALB es alcanzable saltándose CloudFront

> ## Estado: COMPLETO — fases 1 y 2 aplicadas el 2026-07-31
>
> **Fase 1 (20:06).** El SG del ALB quedó con una única regla de entrada: `tcp/80` desde
> la prefix list `pl-0246509e78ddf0729` (`sgr-015f535b404015448`). Se revocaron las reglas
> `80` y `443` desde `0.0.0.0/0`.
>
> **Fase 2 (21:44).** CloudFront inyecta el header `X-Origin-Verify` en el origen
> `Backend-API`, y el listener del ALB lo exige:
>
> | Prioridad | Condición | Acción |
> |---|---|---|
> | 1 | header `X-Origin-Verify` con el valor secreto | `forward` a `bible-tg` |
> | default | — | `fixed-response` 403 |
>
> ARN de la regla:
> `arn:aws:elasticloadbalancing:mx-central-1:274869222183:listener-rule/app/bible-alb/6372b1995d9da0cf/7125b07b7a6f6016/3d7e1478c5908164`
>
> Verificado tras el cambio: `https://escrituraclave.com/` y
> `/api/bible/chapter/1/1` responden 200; el acceso directo al DNS del ALB agota el
> tiempo de espera; el target sigue `healthy`.
>
> **Dónde vive el secreto.** Solo en la configuración de CloudFront. Para recuperarlo:
>
> ```bash
> aws cloudfront get-distribution-config --id E1MTT1XP2UUMYU \
>   --query 'DistributionConfig.Origins.Items[?Id==`Backend-API`].CustomHeaders.Items' \
>   --output json
> ```
>
> No se guardó copia local; los archivos temporales usados durante la aplicación se
> borraron.

Estado inicial verificado (2026-07-31, antes de la fase 1):

- `bible-alb-sg` (`sg-0f48e25801a170bef`) admite 80 y 443 desde `0.0.0.0/0`.
- El ALB solo tiene listener **HTTP:80**, con acción por defecto `forward` a `bible-tg`
  y **ninguna regla adicional**.
- CloudFront `E1MTT1XP2UUMYU` alcanza el ALB por su DNS público como origen `http-only`.

Objetivo: que el ALB solo acepte tráfico proveniente de CloudFront, y solo de *tu*
distribución.

Identificadores confirmados que se usan abajo:

| Recurso | Valor |
|---|---|
| SG del ALB | `sg-0f48e25801a170bef` |
| Regla ingress :80 | `sgr-0761f616f043598e7` |
| Regla ingress :443 | `sgr-0e3a3dd065c9fe995` |
| Prefix list CloudFront (mx-central-1) | `pl-0246509e78ddf0729` |
| Listener | `arn:aws:elasticloadbalancing:mx-central-1:274869222183:listener/app/bible-alb/6372b1995d9da0cf/7125b07b7a6f6016` |
| Target group | `arn:aws:elasticloadbalancing:mx-central-1:274869222183:targetgroup/bible-tg/29e3a2cf8e88e754` |
| Distribución | `E1MTT1XP2UUMYU` |
| DNS del ALB | `bible-alb-1227546912.mx-central-1.elb.amazonaws.com` |

> **Los health checks no se ven afectados por nada de esto.** El balanceador consulta
> `/actuator/health` directamente contra la IP de la task, sin pasar por las reglas del
> listener ni por el security group de entrada.

---

## Fase 1 — Restringir el origen por red

Bajo riesgo y reversible en un comando. Cierra el acceso desde hosts arbitrarios sin
tocar CloudFront, así que **no puede romper producción**.

> `modify-security-group-rules` **no sirve aquí**: AWS rechaza convertir una regla CIDR
> existente en una de prefix list (`InvalidParameterValue: You may not specify
> PrefixListId for an existing IPv4 CIDR rule`). Hay que añadir la nueva y revocar la
> vieja, en ese orden, para no dejar ventana sin acceso.

```bash
export AWS_PAGER=""
SG=sg-0f48e25801a170bef

# 1.1 · Añadir la regla con la prefix list de CloudFront
aws ec2 authorize-security-group-ingress \
  --group-id "$SG" \
  --ip-permissions 'IpProtocol=tcp,FromPort=80,ToPort=80,PrefixListIds=[{PrefixListId=pl-0246509e78ddf0729,Description="Solo origenes CloudFront"}]'

# 1.2 · Comprobar que el sitio sigue arriba ANTES de quitar la regla abierta
curl -sS --ssl-no-revoke -o /dev/null -w 'via cdn: %{http_code}\n' https://escrituraclave.com/

# 1.3 · Revocar "80 desde 0.0.0.0/0"
aws ec2 revoke-security-group-ingress \
  --group-id "$SG" \
  --security-group-rule-ids sgr-0761f616f043598e7

# 1.4 · Revocar la regla 443: no hay listener detrás de ella
aws ec2 revoke-security-group-ingress \
  --group-id "$SG" \
  --security-group-rule-ids sgr-0e3a3dd065c9fe995
```

> En Windows, el `curl` de schannel puede fallar con `CRYPT_E_REVOCATION_OFFLINE` si no
> alcanza el servidor de revocación. Es un problema del cliente, no del sitio; se sortea
> con `--ssl-no-revoke`.

Verificación:

```bash
# Debe mostrar una sola regla de entrada, con PrefixListId y sin CidrIpv4
aws ec2 describe-security-group-rules \
  --filters Name=group-id,Values=sg-0f48e25801a170bef \
  --query 'SecurityGroupRules[?IsEgress==`false`].{Rule:SecurityGroupRuleId,Port:FromPort,Cidr:CidrIpv4,PL:PrefixListId}' \
  --output table

# El acceso directo debe quedar sin respuesta (timeout, no 403 todavía)
curl -sS -o /dev/null -w 'directo: %{http_code}\n' --max-time 10 \
  http://bible-alb-1227546912.mx-central-1.elb.amazonaws.com/actuator/health

# El sitio debe seguir respondiendo normalmente
curl -sS -o /dev/null -w 'via cdn: %{http_code}\n' https://escrituraclave.com/
```

**Reversión de la fase 1:**

```bash
aws ec2 authorize-security-group-ingress --group-id sg-0f48e25801a170bef \
  --protocol tcp --port 80 --cidr 0.0.0.0/0
```

---

## Fase 2 — Exigir un header secreto

La fase 1 deja fuera a cualquier host que no sea CloudFront, pero **no distingue tu
distribución de la de otra cuenta**: cualquiera puede crear una distribución apuntando a
tu ALB y su tráfico saldría de la misma prefix list. El header cierra esa vía.

### 2.1 · Generar el secreto

```bash
SECRET=$(openssl rand -hex 32)
echo "$SECRET"   # guárdalo; lo necesitas en 2.2 y 2.3
```

### 2.2 · Inyectarlo en el origen de CloudFront

```bash
aws cloudfront get-distribution-config --id E1MTT1XP2UUMYU > /tmp/dist.json

SECRET="$SECRET" node -e "
const fs = require('fs');
const d = JSON.parse(fs.readFileSync('/tmp/dist.json','utf8'));
const cfg = d.DistributionConfig;
const o = cfg.Origins.Items.find(x => x.Id === 'Backend-API');
if (!o) throw new Error('no se encontró el origen Backend-API');
o.CustomHeaders = { Quantity: 1, Items: [
  { HeaderName: 'X-Origin-Verify', HeaderValue: process.env.SECRET }
]};
fs.writeFileSync('/tmp/dist-config.json', JSON.stringify(cfg));
fs.writeFileSync('/tmp/etag.txt', d.ETag);
console.log('config preparada, ETag ' + d.ETag);
"

aws cloudfront update-distribution --id E1MTT1XP2UUMYU \
  --distribution-config file:///tmp/dist-config.json \
  --if-match "$(cat /tmp/etag.txt)" \
  --query 'Distribution.Status' --output text
```

Esperar a que el cambio llegue a todos los edges — **no continúes antes de que termine**,
o la fase 2.3 devolverá 403 al tráfico legítimo:

```bash
aws cloudfront wait distribution-deployed --id E1MTT1XP2UUMYU && echo "desplegado"
```

Limpiar los archivos temporales, que contienen el secreto en claro:

```bash
rm -f /tmp/dist.json /tmp/dist-config.json /tmp/etag.txt
```

### 2.3 · Exigirlo en el ALB

El orden importa: primero se crea la regla que deja pasar el tráfico con header, y
**después** se cambia la acción por defecto a 403. Al revés, se corta producción entre un
comando y el siguiente.

```bash
LISTENER='arn:aws:elasticloadbalancing:mx-central-1:274869222183:listener/app/bible-alb/6372b1995d9da0cf/7125b07b7a6f6016'
TG='arn:aws:elasticloadbalancing:mx-central-1:274869222183:targetgroup/bible-tg/29e3a2cf8e88e754'

# Primero: la regla que sí reenvía
aws elbv2 create-rule --listener-arn "$LISTENER" --priority 1 \
  --conditions "[{\"Field\":\"http-header\",\"HttpHeaderConfig\":{\"HttpHeaderName\":\"X-Origin-Verify\",\"Values\":[\"$SECRET\"]}}]" \
  --actions "[{\"Type\":\"forward\",\"TargetGroupArn\":\"$TG\"}]" \
  --query 'Rules[].RuleArn' --output text

# Comprobar que el sitio sigue arriba ANTES de tocar el default
curl -sS -o /dev/null -w 'via cdn: %{http_code}\n' https://escrituraclave.com/

# Después: todo lo que no traiga el header se rechaza
aws elbv2 modify-listener --listener-arn "$LISTENER" \
  --default-actions '[{"Type":"fixed-response","FixedResponseConfig":{"StatusCode":"403","ContentType":"text/plain","MessageBody":"Forbidden"}}]' \
  --query 'Listeners[].DefaultActions[].Type' --output text
```

Verificación final:

```bash
curl -sS -o /dev/null -w 'via cdn: %{http_code}\n' https://escrituraclave.com/
# esperado: 200

curl -sS -o /dev/null -w 'directo sin header: %{http_code}\n' --max-time 10 \
  http://bible-alb-1227546912.mx-central-1.elb.amazonaws.com/actuator/health
# esperado: timeout por la fase 1; y 403 si llegara a pasar la capa de red
```

**Reversión de la fase 2:**

```bash
# Devolver el default a forward
aws elbv2 modify-listener --listener-arn "$LISTENER" \
  --default-actions "[{\"Type\":\"forward\",\"TargetGroupArn\":\"$TG\"}]"

# Borrar la regla del header (usar el ARN que devolvió create-rule)
aws elbv2 delete-rule --rule-arn <RULE_ARN>
```

---

## Consideraciones

**El secreto queda legible en la configuración de CloudFront.** Cualquiera con
`cloudfront:GetDistributionConfig` sobre la cuenta puede leerlo. Es un mecanismo
anti-bypass, no un secreto de alto valor: rótalo periódicamente y no lo reutilices para
otra cosa.

**Queda en el historial del shell.** Tras terminar, conviene limpiar la variable y la
entrada del historial (`unset SECRET`, y revisar `~/.bash_history`).

**Cuota de reglas del security group.** La prefix list de CloudFront consume tantas
entradas de la cuota del SG como direcciones tenga (varias decenas), contra un límite por
defecto de 60 reglas por grupo. Con una sola regla no hay problema, pero si más adelante
agregas muchas reglas a `bible-alb-sg`, ese es el límite que toparás.

**Fase 1 sin fase 2 ya vale la pena.** Si prefieres no tocar CloudFront ahora, la fase 1
sola elimina la exposición a hosts arbitrarios de internet, que es el grueso del riesgo.
La fase 2 solo cubre el caso de que alguien apunte su propia distribución a tu ALB.

**Nada de esto cifra el tramo CloudFront → ALB**, que sigue siendo `http-only`. Para
cerrarlo haría falta un listener 443 en el ALB con certificado ACM y cambiar el origen a
`https-only`. Es un cambio aparte, no incluido en este runbook.

**Este runbook no toca el hallazgo 1.** La base de datos sigue aceptando conexiones desde
`0.0.0.0/0` en el puerto 5432; ver `aws-architecture.md`.
