import com.mongodb.MongoCommandException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import org.bson.Document;

/**
 * Tells you why a MongoDB connection string does not work, without ever printing it.
 *
 * <p>Reads MONGODB_URI and MONGODB_DATABASE from the environment, reports what the string is
 * shaped like, then tries one short connection and names the failure. Nothing it prints contains
 * the username, the password or the host, so its output is safe to paste anywhere.
 *
 * <p>Run it through scripts/check-mongo.ps1, which supplies the driver on the classpath.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 = OK (connection succeeded)</li>
 *   <li>1 = Configuration problem (MONGODB_URI not set, malformed URI, no database named)</li>
 *   <li>2 = Authentication failed (bad credentials or missing permissions)</li>
 *   <li>3 = Network/unreachable (server not reachable, IP not whitelisted, cluster paused)</li>
 *   <li>4 = Other/unknown error</li>
 * </ul>
 */
public final class MongoConnectionDoctor {

    /** Characters that end a URI component, so a password containing one must percent-encode it. */
    private static final String MUST_ENCODE = "@:/?#[]%";

    public static void main(String[] args) {
        String uri = System.getenv("MONGODB_URI");
        String database = System.getenv("MONGODB_DATABASE");

        if (uri == null || uri.isBlank()) {
            fail("MONGODB_URI is not set in this shell.", 1,
                    "Set it in the same window you run this from, or in the IntelliJ run configuration.");
            return;
        }

        System.out.println("Checking the connection string. Nothing below reveals its contents.");
        System.out.println();

        if (!inspect(uri, database)) return;

        System.out.println();
        System.out.println("Connecting (10 second limit)...");
        try (MongoClient client = MongoClients.create(uri)) {
            String target = database != null && !database.isBlank() ? database : "admin";
            client.getDatabase(target).runCommand(new Document("ping", 1));
            System.out.println();
            System.out.println("  OK. Authenticated and reached the database.");
            System.out.println("  The application should start with these settings.");
            System.exit(0);
        } catch (MongoSecurityException authentication) {
            fail("Authentication was refused by the server.", 2,
                    "Check the Atlas database user, its password, and the cluster connection string.",
                    "  - If you just changed the password in Atlas, wait 30 seconds and retry.",
                    "  - Use the Database User password, not your Atlas account password.",
                    "  - Atlas: Database Access -> Edit -> Edit Password. Choose letters and digits",
                    "    only for one test, which rules out an encoding problem entirely.");
        } catch (MongoTimeoutException unreachable) {
            fail("No server answered within the time limit.", 3,
                    "This is usually the network, not the credentials.",
                    "  - Atlas: Network Access -> add your current IP address.",
                    "  - A cluster that was paused takes a few minutes to resume.");
        } catch (MongoCommandException refused) {
            fail("The server answered but refused the command (code " + refused.getErrorCode() + ").", 2,
                    "The credentials are probably valid and the user lacks a permission.",
                    "  - Atlas: Database Access -> Edit -> give the user readWrite on this database.");
        } catch (RuntimeException other) {
            fail("The connection failed: " + other.getClass().getSimpleName(), 4,
                    "The message is not printed here because it can contain the connection string.");
        }
    }

    /** Reports the shape of the string. Returns false when it is malformed enough to stop. */
    private static boolean inspect(String uri, String database) {
        boolean srv = uri.startsWith("mongodb+srv://");
        if (!srv && !uri.startsWith("mongodb://")) {
            fail("The value does not begin with mongodb:// or mongodb+srv://.", 1,
                    "A hosted cluster string normally begins with mongodb+srv://.");
            return false;
        }
        System.out.println("  scheme            " + (srv ? "mongodb+srv (hosted cluster)" : "mongodb (direct)"));

        String rest = uri.substring(uri.indexOf("://") + 3);
        int credentialEnd = rest.lastIndexOf('@');
        if (credentialEnd < 0) {
            System.out.println("  credentials       none in the string");
            System.out.println();
            System.out.println("  A hosted cluster normally needs a username and password.");
            return true;
        }

        String credentials = rest.substring(0, credentialEnd);
        int separator = credentials.indexOf(':');
        System.out.println("  username          " + (separator > 0 ? "present" : "present, but no password follows it"));
        if (separator < 0) {
            System.out.println();
            System.out.println("  There is no ':' between the username and a password.");
            return true;
        }

        String password = credentials.substring(separator + 1);
        System.out.println("  password          present, " + password.length() + " characters");

        StringBuilder offenders = new StringBuilder();
        for (char candidate : MUST_ENCODE.toCharArray()) {
            if (password.indexOf(candidate) >= 0 && !(candidate == '%' && looksEncoded(password))) {
                offenders.append(candidate).append(' ');
            }
        }
        if (offenders.length() > 0) {
            System.out.println();
            System.out.println("  PROBLEM  The password contains characters that must be percent-encoded");
            System.out.println("           inside a connection string: " + offenders.toString().trim());
            System.out.println("           @ becomes %40, : becomes %3A, / becomes %2F, # becomes %23,");
            System.out.println("           % becomes %25. Left raw, the string is parsed wrongly and the");
            System.out.println("           server sees the wrong password.");
            System.out.println("           The simplest fix is a password of letters and digits only.");
        }

        String afterHost = rest.substring(credentialEnd + 1);
        int pathStart = afterHost.indexOf('/');
        String inUri = pathStart < 0 ? "" : afterHost.substring(pathStart + 1).split("\\?")[0];
        System.out.println("  database in URI   " + (inUri.isBlank() ? "none" : "yes"));
        System.out.println("  MONGODB_DATABASE  "
                + (database == null || database.isBlank() ? "not set" : "set to " + database));
        if (inUri.isBlank() && (database == null || database.isBlank())) {
            System.out.println();
            System.out.println("  PROBLEM  No database is named anywhere. Set MONGODB_DATABASE.");
        }
        return true;
    }

    /** A password that already contains %XX is probably encoded, so % is not an offender. */
    private static boolean looksEncoded(String password) {
        return password.matches(".*%[0-9A-Fa-f]{2}.*");
    }

    private static void fail(String headline, int exitCode, String... detail) {
        System.out.println();
        System.out.println("  " + headline);
        for (String line : detail) {
            System.out.println("  " + line);
        }
        System.exit(exitCode);
    }
}
