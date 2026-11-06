{{- define "code-memory.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "code-memory.fullname" -}}
{{- $name := include "code-memory.name" . -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "code-memory.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "code-memory.labels" -}}
helm.sh/chart: {{ include "code-memory.chart" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: {{ include "code-memory.name" . }}
{{- end -}}

{{/* Component selector labels. Call with (dict "ctx" . "component" "app") */}}
{{- define "code-memory.selectorLabels" -}}
app.kubernetes.io/name: {{ include "code-memory.name" .ctx }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* Compose an image ref. Call with (dict "ctx" . "image" .Values.app.image). */}}
{{- define "code-memory.image" -}}
{{- $g := .ctx.Values.global | default dict -}}
{{- $gReg := "" -}}
{{- if $g.registry -}}{{- $gReg = $g.registry.url -}}{{- end -}}
{{- $registry := $gReg | default .image.registry -}}
{{- $repo := .image.repository -}}
{{- $name := .image.name -}}
{{- $tag := .image.tag | default .ctx.Chart.AppVersion -}}
{{- $path := "" -}}
{{- if $repo -}}
{{- $path = printf "%s/%s/%s:%s" $registry $repo $name $tag -}}
{{- else -}}
{{- $path = printf "%s/%s:%s" $registry $name $tag -}}
{{- end -}}
{{- print (regexReplaceAll "/+" $path "/") -}}
{{- end -}}

{{- define "code-memory.pullPolicy" -}}
{{- $g := .ctx.Values.global | default dict -}}
{{- .image.pullPolicy | default $g.imagePullPolicy | default "IfNotPresent" -}}
{{- end -}}

{{- define "code-memory.storageClass" -}}
{{- $g := .ctx.Values.global | default dict -}}
{{- .sc | default $g.storageClass -}}
{{- end -}}

{{- define "code-memory.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "code-memory.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "code-memory.postgres.fullname" -}}
{{- printf "%s-postgres" (include "code-memory.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "code-memory.chunking.fullname" -}}
{{- printf "%s-chunking" (include "code-memory.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "code-memory.postgres.secretName" -}}
{{- if .Values.postgres.auth.existingSecret -}}
{{- .Values.postgres.auth.existingSecret -}}
{{- else -}}
{{- printf "%s-secret" (include "code-memory.postgres.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "code-memory.pullSecrets" -}}
{{- $g := .Values.global | default dict -}}
{{- with $g.pullSecret }}
imagePullSecrets:
  - name: {{ . }}
{{- end }}
{{- end -}}

{{/* Effective parsing mode: explicit override, else derived from chunking topology. */}}
{{- define "code-memory.parsingMode" -}}
{{- if .Values.app.config.parsing.mode -}}
{{- .Values.app.config.parsing.mode -}}
{{- else if eq .Values.chunking.topology "disabled" -}}
java
{{- else -}}
multi
{{- end -}}
{{- end -}}

{{/* Chunking base-url the app should call, per topology ("" when disabled). */}}
{{- define "code-memory.chunking.baseUrl" -}}
{{- $t := .Values.chunking.topology -}}
{{- $port := .Values.chunking.service.port -}}
{{- if eq $t "sidecar" -}}
{{- printf "http://localhost:%v" $port -}}
{{- else if eq $t "service" -}}
{{- printf "http://%s:%v" (include "code-memory.chunking.fullname" .) $port -}}
{{- else if eq $t "external" -}}
{{- required "chunking.external.baseUrl is required when topology=external" .Values.chunking.external.baseUrl -}}
{{- end -}}
{{- end -}}

{{/* Container spec shared by the chunking sidecar and the standalone Deployment. */}}
{{- define "code-memory.chunking.container" -}}
- name: chunking
  image: {{ include "code-memory.image" (dict "ctx" . "image" .Values.chunking.image) }}
  imagePullPolicy: {{ include "code-memory.pullPolicy" (dict "ctx" . "image" .Values.chunking.image) }}
  securityContext:
    {{- toYaml .Values.securityContext | nindent 4 }}
  env:
    - name: PORT
      value: {{ .Values.chunking.service.port | quote }}
  ports:
    - name: http
      containerPort: {{ .Values.chunking.service.port }}
      protocol: TCP
  readinessProbe:
    httpGet:
      path: /health
      port: http
    initialDelaySeconds: 3
    periodSeconds: 10
  livenessProbe:
    httpGet:
      path: /health
      port: http
    initialDelaySeconds: 5
    periodSeconds: 15
  resources:
    {{- toYaml .Values.chunking.resources | nindent 4 }}
{{- end -}}
