# Restaurant Backend Management System

## Project Description

The Restaurant Storage Management System is a web-based application designed to help restaurants manage their inventory, suppliers, stock transactions, and storage activities efficiently. The system provides secure authentication for kitchen staff and managers, inventory management, supplier management, restocking, usage and waste tracking, price history, and management reports. It uses Spring Boot for the production backend, React with Vite for the frontend, and MySQL for database storage. The application helps restaurants monitor stock levels, calculate inventory costs, and make better purchasing decisions.

## Group Members

**GROUP MEMBERS**

Replace the examples below with the student ID and full name of every group member.

- [STUDENT ID] [FULL NAME]
- [STUDENT ID] [FULL NAME]
- [STUDENT ID] [FULL NAME]
- [STUDENT ID] [FULL NAME]

## Technologies Used

| Component | Technology |
|---|---|
| Backend | Java 17+, Spring Boot |
| Frontend | React, Vite |
| Database | MySQL |
| Authentication | JWT |
| Build Tool | Maven |
| Version Control | Git and GitHub |

## Main Features

- Secure JWT authentication and current-user lookup.
- Two user roles: `manager` and `kitchen_staff`.
- Inventory and item management.
- Supplier management.
- Item-supplier management.
- Item price history recording.
- Restock, usage, and waste transactions.
- Weighted-average inventory costing.
- Manager account management.
- Dashboard and inventory reports.
- Low-stock email notification attempts.
- Transactional stock updates.

## Project Structure

```text
restaurant-storage-management/
│
├── pom.xml
├── mvnw
├── mvnw.cmd
├── src/
│   ├── main/
│   └── test/
│
├── frontend/
│   ├── package.json
│   └── src/
│
├── backend/
│   └── db/
│       └── schema.sql
│
├── docs/
├── .gitignore
└── README.md
```

## Requirements

Before running the project, install:

1. Java JDK 17 or newer.
2. MySQL Server.
3. Node.js and npm.
4. Git.

## Installation and Setup

### Step 1: Clone the repository

```bash
git clone https://github.com/YOUR-ACCOUNT/YOUR-REPO.git
cd YOUR-REPO
```

### Step 2: Configure the MySQL database

Create the database and application user using your MySQL administrator account.

The application uses the existing database schema located at:

```text
backend/db/schema.sql
```

Import the schema:

**Windows PowerShell:**

```powershell
mysql -u $env:DB_USER -p $env:DB_NAME < backend/db/schema.sql
```

**Linux / macOS:**

```bash
mysql -u "$DB_USER" -p "$DB_NAME" < backend/db/schema.sql
```

The Spring Boot application does not automatically create or migrate database tables.

Do not use the legacy destructive Node seed against a production database.

### Step 3: Configure environment variables

Configure the following variables in your environment or secret manager:

| Variable | Description |
|---|---|
| DB_HOST | MySQL host |
| DB_PORT | MySQL port (default: 3306) |
| DB_NAME | Database name |
| DB_USER | Database username |
| DB_PASSWORD | Database password |
| PORT | Backend port (default: 5001) |
| JWT_SECRET | Private JWT signing secret (at least 32 bytes) |
| PO_EMAIL | Low-stock notification recipient |
| SMTP_HOST | SMTP server |
| SMTP_PORT | SMTP server port |
| SMTP_SECURE | SMTP security configuration |
| SMTP_USER | SMTP username |
| SMTP_PASSWORD | SMTP password |
| MAIL_FROM | Optional sender email |
| RESTAURANT_TIME_ZONE | Report timezone (default: Asia/Bangkok) |
| CORS_ALLOWED_ORIGINS | Allowed frontend origins |

Additional optional Gmail credential fallbacks:

- `GMAIL_USER`
- `GMAIL_APP_PASSWORD`

**Important:** Never commit `.env` files, passwords, API keys, or private tokens to GitHub.

The `.env.example` file is provided only as a reference for environment variable names. Spring Boot does not automatically load `.env` files.

### Step 4: Build and run the backend

From the repository root:

**Windows:**

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

**Linux / macOS:**

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

The backend runs at:

```text
http://localhost:5001
```

The API base URL is:

```text
http://localhost:5001/api
```

### Step 5: Run the frontend

Open another terminal:

```bash
cd frontend
npm ci
npm run dev
```

The frontend runs at:

```text
http://localhost:5173
```

The frontend communicates with the Spring Boot backend at:

```text
http://localhost:5001/api
```

If the backend is hosted elsewhere, configure `VITE_API_URL` accordingly.

## Demo Login Accounts

The application supports two roles:

| Role | Access |
|---|---|
| Manager | Account management, reports, inventory management, and stock transactions |
| Kitchen Staff | Inventory reading and stock transactions |

Optional demo accounts can be created by setting:

```env
SEED_USERS_ENABLED=true
USER_PO_PASSWORD=your_manager_password
USER_STAFF_PASSWORD=your_staff_password
```

Optional login names:

```env
USER_PO_LOGIN=manager
USER_STAFF_LOGIN=kitchen_staff
```

Start the backend once to create or update the demo accounts, then disable the seed flag.

The passwords must be configured through environment variables and must never be committed to GitHub.

## API Endpoints

All API routes use the `/api` prefix.

Except for `POST /auth/login`, API routes require a valid bearer JWT.

| Method | Endpoint | Description |
|---|---|---|
| POST | `/auth/login` | User login |
| GET | `/auth/me` | Current user |
| GET, POST | `/accounts` | Account management |
| PUT | `/accounts/:id` | Update account |
| PUT | `/accounts/:id/password` | Update password |
| PUT | `/accounts/:id/active` | Change account status |
| GET, POST | `/items` | Item management |
| GET, PUT, DELETE | `/items/:id` | Individual item operations |
| GET | `/items/:id/price-history` | Item price history |
| GET, POST | `/suppliers` | Supplier management |
| GET, POST | `/item-suppliers` | Item-supplier management |
| GET | `/storage-logs` | Storage transaction history |
| POST | `/stock/bulk-change` | Stock transactions |
| GET | `/reports/dashboard` | Dashboard data |
| GET | `/reports/usage` | Usage report |
| GET | `/reports/waste` | Waste report |
| GET | `/reports/restock-suggestions` | Restock suggestions |
| GET | `/reports/what-to-buy` | Purchasing recommendations |

**Role permissions:**

- Managers can manage accounts, items, suppliers, item-supplier relationships, price history, and reports.
- Both managers and kitchen staff can read inventory and submit stock changes.

## Group Member Responsibilities

Each group member should be listed with their actual contributions.

| Group Member | Responsibilities |
|---|---|
| [Hein Zar Ne] | [Actual features and tasks] |
| [Nyo Naing Loon] | Implemented CRUD operations for the Items Controller, configured file handling, and designed and created the database.|
| [Thu Myat Hein] | [CRUD for reports Controller ] |
| [Phone Thit Tun] | [Actual features and tasks] |

## Database Information

The MySQL database name is:

```text
restaurant_storage
```

The existing database contract is maintained in:

```text
backend/db/schema.sql
```

The production Spring Boot backend does not automatically create or migrate database tables.

Inventory transactions are handled transactionally, with server-derived user identity and weighted-average item costing.

## Security

- JWT-based authentication.
- Role-based authorization.
- Passwords and secrets are provided through environment variables.
- Database credentials are not stored in source code.
- Protected API endpoints require authentication.

## Important Notes

- The Spring Boot application is the production backend.
- The frontend and backend run separately during local development.
- The database schema must be imported before starting the application.
- The legacy Node.js demo-data seed is not part of the production workflow.
- Do not drop production databases or delete `storage_logs` during deployment.

## License

This project was developed as a university group project at Mae Fah Luang University.