import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

public class TestSSL {
    public static void main(String[] args) throws Exception {
        URL url = new URL("https://maven.google.com");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.connect();
        System.out.println("Response: " + conn.getResponseCode());
    }
}
