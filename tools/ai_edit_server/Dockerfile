# Build from the repository root.
# CPU fake mode needs no pip, GPU, model weights, secrets, or paid endpoints.
FROM python:3.11-slim
ARG INSTALL_EXTRA=none
WORKDIR /srv
COPY tools/ai_edit_server /srv/tools/ai_edit_server
RUN if [ "$INSTALL_EXTRA" = "gpu" ]; then \
      pip install --no-cache-dir -r /srv/tools/ai_edit_server/requirements-gpu.txt; \
    elif [ "$INSTALL_EXTRA" = "comfy" ]; then \
      pip install --no-cache-dir -r /srv/tools/ai_edit_server/requirements-comfy.txt; \
    elif [ "$INSTALL_EXTRA" != "none" ]; then \
      echo 'INSTALL_EXTRA must be none, gpu or comfy' >&2; exit 1; \
    fi
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 ATLAS_BACKEND=fake
EXPOSE 8080
CMD ["python3", "-m", "tools.ai_edit_server.server", "--host", "0.0.0.0", "--port", "8080"]
