import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main {

    // Stores the project's root directory.
    static final String ROOT = System.getProperty("user.dir");

    // Stores active login sessions.
    static final Map<String, UserSession> SESSIONS =
            new ConcurrentHashMap<>();

    // Represents a logged-in user's ID, name, and role.
    static class UserSession {
        int id;
        String name, role;

        UserSession(int i, String n, String r) {
            id = i;
            name = n;
            role = r;
        }
    }

    /*
     * Main function:
     * Starts the HTTP server on port 8080 and registers
     * the URL routes for the website.
     */
    public static void main(String[] args) throws Exception {

        HttpServer s = HttpServer.create(
                new InetSocketAddress(8080), 0);

        s.createContext("/", e -> file(e, "website/index.html"));
        s.createContext("/login", Main::login);
        s.createContext("/register", Main::register);

        // Logs out the user and removes the active session.
        s.createContext("/logout", e -> {
            String t = cookie(e);
            if (t != null) SESSIONS.remove(t);

            e.getResponseHeaders().add(
                    "Set-Cookie",
                    "PETSESSION=; Path=/; Max-Age=0");

            redirect(e, "/");
        });

        // Routes for the three user roles.
        s.createContext("/admin", Main::admin);
        s.createContext("/shelter", Main::shelter);
        s.createContext("/adopter", Main::adopter);

        // Routes for pets and adoption applications.
        s.createContext("/addpet", Main::addpet);
        s.createContext("/pets", Main::pets);
        s.createContext("/apply", Main::apply);
        s.createContext("/applications", Main::applications);

        // Routes for administrator management pages.
        s.createContext("/admin/pets", Main::adminPets);
        s.createContext("/admin/applications", Main::adminApps);
        s.createContext("/admin/users", Main::adminUsers);
        s.createContext("/admin/action", Main::adminAction);

        // Serves files from the website directory.
        s.createContext("/website/", e ->
                file(e, e.getRequestURI().getPath().substring(1)));

        // Starts the server.
        s.start();

        System.out.println("Open http://localhost:8080");
        System.out.println("Press Ctrl+C to stop.");
    }

    /*
     * Login function:
     * Checks the user's credentials and account status.
     * Creates a session and redirects the user according to their role.
     */
    static void login(HttpExchange e) throws IOException {
        if (get(e)) {
            file(e, "website/login.html");
            return;
        }

        Map<String, String> f = form(e);

        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "SELECT id,name,role,status FROM users WHERE email=? AND password=?")) {

            p.setString(1, f.get("email"));
            p.setString(2, f.get("password"));

            try (ResultSet r = p.executeQuery()) {
                if (!r.next() ||
                        !"ACTIVE".equals(r.getString("status"))) {
                    msg(e, "Login failed",
                            "Check your details or contact admin.",
                            "/login", "Try again");
                    return;
                }

                UserSession u = new UserSession(
                        r.getInt("id"),
                        r.getString("name"),
                        r.getString("role"));

                String token = UUID.randomUUID().toString();
                SESSIONS.put(token, u);

                e.getResponseHeaders().add(
                        "Set-Cookie",
                        "PETSESSION=" + token +
                        "; Path=/; HttpOnly; SameSite=Lax");

                redirect(e,
                        u.role.equals("ADMIN") ? "/admin" :
                        u.role.equals("SHELTER") ? "/shelter" :
                        "/adopter");
            }

        } catch (Exception x) {
            msg(e, "Database error", x.getMessage(),
                    "/login", "Back");
        }
    }

    /*
     * Registration function:
     * Registers a new adopter or shelter by saving their details
     * in the users table.
     */
    static void register(HttpExchange e) throws IOException {
        if (get(e)) {
            file(e, "website/register.html");
            return;
        }

        Map<String, String> f = form(e);
        String role = f.getOrDefault("role", "ADOPTER");

        if (!role.equals("ADOPTER") && !role.equals("SHELTER")) {
            msg(e, "Error", "Choose Adopter or Shelter.",
                    "/register", "Back");
            return;
        }

        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO users(name,email,password,role,phone,address) VALUES(?,?,?,?,?,?)")) {

            p.setString(1, f.get("name"));
            p.setString(2, f.get("email"));
            p.setString(3, f.get("password"));
            p.setString(4, role);
            p.setString(5, f.get("phone"));
            p.setString(6, f.get("address"));
            p.executeUpdate();

            msg(e, "Registered", "Now log in.",
                    "/login", "Login");

        } catch (Exception x) {
            msg(e, "Registration failed",
                    "Email may already exist. " + x.getMessage(),
                    "/register", "Try again");
        }
    }

    /*
     * Admin dashboard:
     * Displays links for managing users, pet listings,
     * and adoption applications.
     */
    static void admin(HttpExchange e) throws IOException {
        UserSession u = need(e, "ADMIN");
        if (u == null) return;

        layout(e, u, "Admin Dashboard",
                "<h1>Welcome, Admin! 🐾</h1>" +
                "<p>Manage users, pet listings and adoption applications.</p>" +
                "<div class='cards'>" +
                "<div class='card'><h2>👥 Users</h2>" +
                "<a class='btn' href='/admin/users'>Manage Users</a></div>" +
                "<div class='card'><h2>🐶 Pets</h2>" +
                "<a class='btn' href='/admin/pets'>Approve Pet Listings</a></div>" +
                "<div class='card'><h2>📋 Applications</h2>" +
                "<a class='btn' href='/admin/applications'>Review Applications</a></div>" +
                "</div>");
    }

    /*
     * Shelter dashboard:
     * Displays the shelter's pet listings and their statuses.
     */
    static void shelter(HttpExchange e) throws IOException {
        UserSession u = need(e, "SHELTER");
        if (u == null) return;

        layout(e, u, "Shelter",
                "<h1>Welcome, Shelter! 🏠</h1>" +
                "<p>Add pets and wait for admin approval.</p>" +
                "<a class='btn' href='/addpet'>Add Pet</a>" +
                "<h2>My Pet Listings</h2>" +
                table("SELECT name,breed,age,status FROM pets WHERE shelter_id=" + u.id,
                        new String[]{"name", "breed", "age", "status"}));
    }

    /*
     * Adopter dashboard:
     * Provides links to browse available pets and view applications.
     */
    static void adopter(HttpExchange e) throws IOException {
        UserSession u = need(e, "ADOPTER");
        if (u == null) return;

        layout(e, u, "Adopter",
                "<h1>Welcome, " + esc(u.name) + "! ❤️</h1>" +
                "<p>Find a pet and apply for adoption.</p>" +
                "<a class='btn' href='/pets'>Browse Pets</a> " +
                "<a class='btn' href='/applications'>My Applications</a>");
    }

    /*
     * Add-pet function:
     * Displays the pet form or inserts a new pet listing.
     * New listings receive PENDING status for admin review.
     */
    static void addpet(HttpExchange e) throws IOException {
        UserSession u = need(e, "SHELTER");
        if (u == null) return;

        if (get(e)) {
            file(e, "website/addpet.html");
            return;
        }

        Map<String, String> f = form(e);

        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO pets(name,breed,age,description,shelter_id,status) VALUES(?,?,?,?,?,'PENDING')")) {

            p.setString(1, f.get("name"));
            p.setString(2, f.get("breed"));
            p.setInt(3, Integer.parseInt(f.get("age")));
            p.setString(4, f.get("description"));
            p.setInt(5, u.id);
            p.executeUpdate();

            msg(e, "Pet submitted",
                    "Admin will review your pet listing.",
                    "/shelter", "Back");

        } catch (Exception x) {
            msg(e, "Error", x.getMessage(),
                    "/addpet", "Try again");
        }
    }

    /*
     * Pets function:
     * Retrieves approved pets and displays their information.
     * Adopters can submit an application from each pet listing.
     */
    static void pets(HttpExchange e) throws IOException {
        UserSession u = current(e);

        StringBuilder b = new StringBuilder(
                "<h1>🐶 Available Pets</h1><div class='cards'>");

        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "SELECT p.*,u.name shelter FROM pets p JOIN users u ON p.shelter_id=u.id WHERE p.status='APPROVED'");
             ResultSet r = p.executeQuery()) {

            while (r.next()) {
                b.append("<div class='card'><h2>")
                        .append(esc(r.getString("name")))
                        .append("</h2><p>Breed: ")
                        .append(esc(r.getString("breed")))
                        .append("</p><p>Age: ")
                        .append(r.getInt("age"))
                        .append("</p><p>")
                        .append(esc(r.getString("description")))
                        .append("</p><p>Shelter: ")
                        .append(esc(r.getString("shelter")))
                        .append("</p>");

                // Show the adoption form only to logged-in adopters.
                if (u != null && u.role.equals("ADOPTER")) {
                    b.append("<form action='/apply' method='post'><input type='hidden' name='petId' value='")
                            .append(r.getInt("id"))
                            .append("'><textarea name='message' placeholder='Why would you like to adopt?' required></textarea><button>Apply for Adoption</button></form>");
                }

                b.append("</div>");
            }

        } catch (Exception x) {
            b.append(esc(x.getMessage()));
        }

        b.append("</div>");
        layout(e, u, "Available Pets", b.toString());
    }

    /*
     * Apply function:
     * Stores an adoption application for an approved pet.
     */
    static void apply(HttpExchange e) throws IOException {
        UserSession u = need(e, "ADOPTER");
        if (u == null) return;

        Map<String, String> f = form(e);

        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO applications(pet_id,adopter_id,message) SELECT id,?,? FROM pets WHERE id=? AND status='APPROVED'")) {

            p.setInt(1, u.id);
            p.setString(2, f.get("message"));
            p.setInt(3, Integer.parseInt(f.get("petId")));

            if (p.executeUpdate() == 0) {
                msg(e, "Not available",
                        "Pet was not found or is not approved.",
                        "/pets", "Back");
            } else {
                msg(e, "Application sent",
                        "Your request is now in the admin application list.",
                        "/applications", "My Applications");
            }

        } catch (Exception x) {
            msg(e, "Error", x.getMessage(), "/pets", "Back");
        }
    }

    /*
     * Applications function:
     * Displays applications belonging to the adopter.
     * Administrators are directed to the application management page.
     */
    static void applications(HttpExchange e) throws IOException {
        UserSession u = current(e);

        if (u == null) {
            redirect(e, "/login");
            return;
        }

        if (u.role.equals("ADMIN")) {
            adminApps(e);
            return;
        }

        String where = u.role.equals("ADOPTER")
                ? " WHERE a.adopter_id=" + u.id : "";

        layout(e, u, "Applications",
                "<h1>📋 Applications</h1>" +
                table("SELECT p.name pet,u.name adopter,a.message,a.status FROM applications a JOIN pets p ON p.id=a.pet_id JOIN users u ON u.id=a.adopter_id" + where,
                        new String[]{"pet", "adopter", "message", "status"}));
    }

    /*
     * Admin-pets function:
     * Opens the page where administrators review pet listings.
     */
    static void adminPets(HttpExchange e) throws IOException {
        UserSession u = need(e, "ADMIN");
        if (u == null) return;

        layout(e, u, "Manage Pets",
                "<h1>🐶 Manage Pet Listings</h1>" +
                "<p>Approve or reject shelter listings.</p>" +
                petAdminTable());
    }

    /*
     * Pet-admin-table function:
     * Creates a table of pet listings with their current statuses
     * and action buttons for pending listings.
     */
    static String petAdminTable() {
        StringBuilder b = new StringBuilder(
                "<table><tr><th>Pet</th><th>Breed</th><th>Age</th><th>Shelter</th><th>Status</th><th>Action</th></tr>");

        try (Connection c = DBConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                     "SELECT p.*,u.name shelter FROM pets p JOIN users u ON u.id=p.shelter_id ORDER BY p.id DESC")) {

            while (r.next()) {
                b.append("<tr><td>")
                        .append(esc(r.getString("name")))
                        .append("</td><td>")
                        .append(esc(r.getString("breed")))
                        .append("</td><td>")
                        .append(r.getInt("age"))
                        .append("</td><td>")
                        .append(esc(r.getString("shelter")))
                        .append("</td><td>")
                        .append(esc(r.getString("status")))
                        .append("</td><td>");

                if (r.getString("status").equals("PENDING")) {
                    b.append(action(r.getInt("id"), "pet", "APPROVED", "Approve"))
                            .append(action(r.getInt("id"), "pet", "REJECTED", "Reject"));
                }

                b.append("</td></tr>");
            }

        } catch (Exception x) {
            b.append("<tr><td>")
                    .append(esc(x.getMessage()))
                    .append("</td></tr>");
        }

        return b.append("</table>").toString();
    }

    /*
     * Admin-apps function:
     * Displays all adoption applications and review controls
     * for applications that are still pending.
     */
    static void adminApps(HttpExchange e) throws IOException {
        UserSession u = need(e, "ADMIN");
        if (u == null) return;

        StringBuilder b = new StringBuilder(
                "<h1>📋 Adoption Applications</h1><table><tr><th>Pet</th><th>Adopter</th><th>Message</th><th>Status</th><th>Action</th></tr>");

        try (Connection c = DBConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                     "SELECT a.*,p.name pet,u.name adopter FROM applications a JOIN pets p ON p.id=a.pet_id JOIN users u ON u.id=a.adopter_id ORDER BY a.id DESC")) {

            while (r.next()) {
                b.append("<tr><td>")
                        .append(esc(r.getString("pet")))
                        .append("</td><td>")
                        .append(esc(r.getString("adopter")))
                        .append("</td><td>")
                        .append(esc(r.getString("message")))
                        .append("</td><td>")
                        .append(esc(r.getString("status")))
                        .append("</td><td>");

                if (r.getString("status").equals("PENDING")) {
                    b.append(action(r.getInt("id"), "application", "APPROVED", "Approve"))
                            .append(action(r.getInt("id"), "application", "REJECTED", "Reject"));
                }

                b.append("</td></tr>");
            }

        } catch (Exception x) {
            b.append("<tr><td>")
                    .append(esc(x.getMessage()))
                    .append("</td></tr>");
        }

        layout(e, u, "Manage Applications",
                b.append("</table>").toString());
    }

    /*
     * Admin-users function:
     * Lists registered users and lets the administrator activate
     * or deactivate non-administrator accounts.
     */
    static void adminUsers(HttpExchange e) throws IOException {
        UserSession u = need(e, "ADMIN");
        if (u == null) return;

        StringBuilder b = new StringBuilder(
                "<h1>👥 Manage Users</h1><table><tr><th>Name</th><th>Email</th><th>Phone</th><th>Address</th><th>Role</th><th>Status</th><th>Action</th></tr>");

        try (Connection c = DBConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT * FROM users ORDER BY id")) {

            while (r.next()) {
                b.append("<tr><td>")
                        .append(esc(r.getString("name")))
                        .append("</td><td>")
                        .append(esc(r.getString("email")))
                        .append("</td><td>")
                        .append(esc(r.getString("phone")))
                        .append("</td><td>")
                        .append(esc(r.getString("address")))
                        .append("</td><td>")
                        .append(esc(r.getString("role")))
                        .append("</td><td>")
                        .append(esc(r.getString("status")))
                        .append("</td><td>");

                if (!r.getString("role").equals("ADMIN")) {
                    boolean active = r.getString("status").equals("ACTIVE");

                    b.append(action(r.getInt("id"), "user",
                            active ? "INACTIVE" : "ACTIVE",
                            active ? "Disable" : "Activate"));
                }

                b.append("</td></tr>");
            }

        } catch (Exception x) {
            b.append("<tr><td>")
                    .append(esc(x.getMessage()))
                    .append("</td></tr>");
        }

        layout(e, u, "Manage Users",
                b.append("</table>").toString());
    }

    /*
     * Admin-action function:
     * Processes administrator requests to update pet statuses,
     * application statuses, and user account statuses.
     */
    static void adminAction(HttpExchange e) throws IOException {
        UserSession u = need(e, "ADMIN");
        if (u == null) return;

        Map<String, String> f = form(e);
        String type = f.get("type");
        String action = f.get("action");

        try (Connection c = DBConnection.getConnection()) {

            if ("pet".equals(type) &&
                    Arrays.asList("APPROVED", "REJECTED").contains(action)) {

                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE pets SET status=? WHERE id=?")) {
                    p.setString(1, action);
                    p.setInt(2, Integer.parseInt(f.get("id")));
                    p.executeUpdate();
                }

                redirect(e, "/admin/pets");

            } else if ("application".equals(type) &&
                    Arrays.asList("APPROVED", "REJECTED").contains(action)) {

                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE applications SET status=? WHERE id=? AND status='PENDING'")) {
                    p.setString(1, action);
                    p.setInt(2, Integer.parseInt(f.get("id")));
                    p.executeUpdate();
                }

                redirect(e, "/admin/applications");

            } else if ("user".equals(type) &&
                    Arrays.asList("ACTIVE", "INACTIVE").contains(action)) {

                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE users SET status=? WHERE id=? AND role<>'ADMIN'")) {
                    p.setString(1, action);
                    p.setInt(2, Integer.parseInt(f.get("id")));
                    p.executeUpdate();
                }

                redirect(e, "/admin/users");

            } else {
                redirect(e, "/admin");
            }

        } catch (Exception x) {
            msg(e, "Update failed", x.getMessage(),
                    "/admin", "Back");
        }
    }

    /*
     * Action function:
     * Creates a form with hidden fields for the selected record,
     * record type, and action to send to the admin-action route.
     */
    static String action(int id, String type,
                         String value, String label) {
        return "<form class='inline' action='/admin/action' method='post'>" +
                "<input type='hidden' name='id' value='" + id + "'>" +
                "<input type='hidden' name='type' value='" + type + "'>" +
                "<input type='hidden' name='action' value='" + value + "'>" +
                "<button class='small'>" + label + "</button></form>";
    }

    /*
     * Table function:
     * Executes a SQL query and converts its result into an HTML table.
     */
    static String table(String sql, String[] cols) {
        StringBuilder b = new StringBuilder("<table><tr>");

        for (String col : cols) {
            b.append("<th>").append(esc(col)).append("</th>");
        }

        b.append("</tr>");

        try (Connection c = DBConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {

            while (r.next()) {
                b.append("<tr>");

                for (String col : cols) {
                    b.append("<td>")
                            .append(esc(r.getString(col)))
                            .append("</td>");
                }

                b.append("</tr>");
            }

        } catch (Exception x) {
            b.append("<tr><td>")
                    .append(esc(x.getMessage()))
                    .append("</td></tr>");
        }

        return b.append("</table>").toString();
    }

    /*
     * Need function:
     * Verifies login status and checks whether the user has
     * the required role to access a page.
     */
    static UserSession need(HttpExchange e, String role)
            throws IOException {
        UserSession u = current(e);

        if (u == null) {
            redirect(e, "/login");
            return null;
        }

        if (!u.role.equals(role)) {
            msg(e, "Access denied",
                    "This page is only for " + role.toLowerCase() + ".",
                    "/", "Home");
            return null;
        }

        return u;
    }

    /*
     * Current function:
     * Retrieves the logged-in user's session using the session cookie.
     */
    static UserSession current(HttpExchange e) {
        String t = cookie(e);
        return t == null ? null : SESSIONS.get(t);
    }

    /*
     * Cookie function:
     * Extracts the PETSESSION token from the request's Cookie header.
     */
    static String cookie(HttpExchange e) {
        List<String> cs = e.getRequestHeaders().get("Cookie");

        if (cs != null) {
            for (String line : cs) {
                for (String part : line.split(";")) {
                    String[] a = part.trim().split("=", 2);

                    if (a.length == 2 && a[0].equals("PETSESSION")) {
                        return a[1];
                    }
                }
            }
        }

        return null;
    }

    /*
     * Form function:
     * Reads URL-encoded form data and stores the fields
     * as key-value pairs in a Map.
     */
    static Map<String, String> form(HttpExchange e)
            throws IOException {
        String raw = new String(
                e.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);

        Map<String, String> m = new HashMap<>();

        for (String part : raw.split("&")) {
            String[] a = part.split("=", 2);

            if (a.length == 2) {
                m.put(
                        URLDecoder.decode(a[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(a[1], StandardCharsets.UTF_8));
            }
        }

        return m;
    }

    /*
     * GET function:
     * Checks whether the HTTP request uses the GET method.
     */
    static boolean get(HttpExchange e) {
        return e.getRequestMethod().equalsIgnoreCase("GET");
    }

    /*
     * File function:
     * Reads an HTML or CSS file and sends it to the browser.
     */
    static void file(HttpExchange e, String path) throws IOException {
        File f = new File(ROOT, path);

        if (!f.exists()) {
            msg(e, "File not found",
                    f.getAbsolutePath(), "/", "Home");
            return;
        }

        byte[] b = Files.readAllBytes(f.toPath());

        e.getResponseHeaders().set(
                "Content-Type",
                path.endsWith(".css")
                        ? "text/css; charset=UTF-8"
                        : "text/html; charset=UTF-8");

        e.sendResponseHeaders(200, b.length);

        try (OutputStream o = e.getResponseBody()) {
            o.write(b);
        }
    }

    /*
     * Layout function:
     * Builds the common HTML page structure and role-based
     * navigation links for the website.
     */
    static void layout(HttpExchange e, UserSession u,
                       String title, String body) throws IOException {
        String nav = "<a href='/'>Home</a> <a href='/pets'>Browse Pets</a> ";

        if (u != null) {
            if (u.role.equals("ADMIN")) {
                nav += "<a href='/admin'>Dashboard</a> <a href='/admin/users'>Users</a> <a href='/admin/pets'>Pets</a> <a href='/admin/applications'>Applications</a> ";
            } else if (u.role.equals("SHELTER")) {
                nav += "<a href='/shelter'>Dashboard</a> <a href='/addpet'>Add Pet</a> ";
            } else {
                nav += "<a href='/adopter'>Dashboard</a> <a href='/applications'>My Applications</a> ";
            }

            nav += "<a href='/logout'>Logout</a>";
        } else {
            nav += "<a href='/login'>Login</a> <a href='/register'>Register</a>";
        }

        send(e,
                "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>" + esc(title) + "</title>" +
                "<link rel='stylesheet' href='/website/style.css'>" +
                "</head><body><nav><b>🐾 Pet Adoption</b><div>" + nav +
                "</div></nav><main>" + body + "</main>" +
                "<footer>Pet Adoption Platform • Every Pet Deserves a Home 🐾</footer>" +
                "</body></html>");
    }

    /*
     * Message function:
     * Displays a response page with a heading, message,
     * and a link to continue navigating the website.
     */
    static void msg(HttpExchange e, String title, String text,
                    String link, String label) throws IOException {
        send(e,
                "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<link rel='stylesheet' href='/website/style.css'>" +
                "</head><body><div class='box'><h1>" + esc(title) +
                "</h1><p>" + esc(text) + "</p><a class='btn' href='" +
                link + "'>" + esc(label) +
                "</a></div></body></html>");
    }

    /*
     * Send function:
     * Sends an HTML string as a UTF-8 HTTP response.
     */
    static void send(HttpExchange e, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);

        e.getResponseHeaders().set(
                "Content-Type", "text/html; charset=UTF-8");

        e.sendResponseHeaders(200, b.length);

        try (OutputStream o = e.getResponseBody()) {
            o.write(b);
        }
    }

    /*
     * Redirect function:
     * Redirects the browser to another URL using an HTTP 302 response.
     */
    static void redirect(HttpExchange e, String loc) throws IOException {
        e.getResponseHeaders().set("Location", loc);
        e.sendResponseHeaders(302, -1);
        e.close();
    }

    /*
     * Escape function:
     * Converts special characters into HTML entities before
     * displaying user-provided text, helping prevent HTML injection.
     */
    static String esc(String x) {
        if (x == null) return "";

        return x.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}