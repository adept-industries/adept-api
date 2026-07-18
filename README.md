# Adept API

The Adept API is the public backend service for the Adept developer-delivery analytics platform.

## Responsibilities

This repository owns:

- authentication and sessions;
- users, workspaces, memberships, and authorization;
- GitHub and Jira integration orchestration;
- public REST API endpoints;
- webhook request verification and acceptance;
- Flyway database migrations;
- the canonical local Docker Compose configuration;
- OpenAPI contract generation.

This repository does not own:

- the React frontend;
- machine-learning model training;
- risk-model inference logic;
- DORA calculation worker logic.

## Technology baseline

- Java 25
- Spring Boot 4.1.0
- Maven Wrapper
- PostgreSQL 18
- Flyway

## Current status

Phase 0 repository foundation.

The Spring Boot application, Maven Wrapper, database schema, Docker Compose files, and application run commands will be added during Phase 1.

## Contribution

All changes must be made through a feature branch and pull request after branch protection is enabled.