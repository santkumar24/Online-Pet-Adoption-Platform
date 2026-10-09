# Online Pet Adoption Platform

Simple Java + HTML/CSS + MySQL project. Uses Java built-in `HttpServer`; Tomcat is not used.

## Features
- Adopter: register with name, email, phone and address; browse approved pets; apply; check application status.
- Shelter: register with name, email, phone and address; submit pet listings; see approval status.
- Admin: view registered user details (name, email, phone, address, role, status), activate/disable accounts, approve/reject pet listings and adoption applications.

## Setup
1. Run `database/petadoption.sql` in MySQL Workbench.
2. If you already created the old database, run these two SQL commands once:
   `ALTER TABLE users ADD COLUMN phone VARCHAR(30) NOT NULL DEFAULT '';`
   `ALTER TABLE users ADD COLUMN address VARCHAR(255) NOT NULL DEFAULT '';`
   Do not run them if those columns already exist.
3. Put `mysql-connector-j-26.7.0.jar` inside `lib/`.
4. In `src/DBConnection.java`, replace `YOUR_MYSQL_PASSWORD` with your MySQL password.
5. Open this folder in VS Code and run `run.bat`.
6. Open http://localhost:8080.

## Admin login
Email: `admin@gmail.com`
Password: `admin123`

## Workflow
Shelter registers -> adds a pet -> admin approves pet listing -> adopter registers and applies -> admin approves/rejects application -> adopter checks status.


