.PHONY: demo infra ps logs down reset

BASH := bash
ifeq ($(OS),Windows_NT)
BASH := C:/Progra~1/Git/bin/bash.exe
endif

demo:
	$(BASH) scripts/demo.sh preflight
	$(BASH) -c '[[ -f .env ]] || cp .env.example .env'
	docker compose up -d --build --wait
	$(BASH) scripts/demo.sh

infra:
	docker compose up -d postgres rabbitmq

ps:
	docker compose ps

logs:
	docker compose logs -f

down:
	docker compose down

reset:
	docker compose down -v
