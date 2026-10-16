import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Read-only empty-store check; URL is an environment secret, never a command-line argument. */
public class RedisIsolationCheck {
    static Object read(InputStream input) throws IOException {
        int type=input.read();var text=new StringBuilder();int value;
        while((value=input.read())!='\r'){
            if(value<0 || text.length()>1024)throw new IOException("Invalid RESP");text.append((char)value);
        }
        if(input.read()!='\n')throw new IOException("Invalid RESP terminator");
        if(type=='+')return text.toString();
        if(type==':')return Long.valueOf(text.toString());
        throw new IOException("Unexpected Redis response");
    }
    static Object command(Socket socket,String... args)throws IOException{
        var output=socket.getOutputStream();
        output.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.UTF_8));
        for(String arg:args){var bytes=arg.getBytes(StandardCharsets.UTF_8);
            output.write(("$"+bytes.length+"\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(bytes);output.write(new byte[]{13,10});}
        output.flush();return read(socket.getInputStream());
    }
    static int database(URI uri){
        boolean local=java.util.Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost());
        if(!"rediss".equals(uri.getScheme()) && !(local && "redis".equals(uri.getScheme())))
            throw new IllegalArgumentException("External Redis requires verified TLS");
        String path=uri.getPath();int database=(path==null || path.isEmpty() || path.equals("/"))?0:Integer.parseInt(path.substring(1));
        if(database<0 || database>15)throw new IllegalArgumentException("Invalid Redis database");
        return database;
    }
    static String endpoint(URI uri){
        String host=uri.getHost();
        if(host==null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException("Invalid Redis endpoint");
        if(java.util.Set.of("localhost","127.0.0.1","::1","[::1]").contains(host))host="loopback";
        return host.toLowerCase(Locale.ROOT)+":"+(uri.getPort()<0?6379:uri.getPort());
    }
    public static void main(String[] args){
        boolean restore=args.length==1 && args[0].equals("--restore");
        boolean ping=args.length==1 && args[0].equals("--ping");
        try{
            if(args.length==1 && args[0].equals("--self-test")){
                if(!Long.valueOf(0).equals(read(new ByteArrayInputStream(":0\r\n".getBytes(StandardCharsets.UTF_8)))))throw new AssertionError();
                if(database(URI.create("redis://127.0.0.1:16380/14"))!=14)throw new AssertionError();
                if(database(URI.create("rediss://default:fixture@example.com:6379/"))!=0)throw new AssertionError();
                try{database(URI.create("redis://example.com/0"));throw new AssertionError();}catch(IllegalArgumentException expected){}
                System.out.println("REDIS_ISOLATION_CHECK_SELF_TEST=OK");return;
            }
            if(args.length!=0 && !restore && !ping)throw new IllegalArgumentException("Invalid mode");
            URI uri=URI.create(System.getenv(restore?"RESTORE_REDIS_URL":ping?"REDIS_URL":"CAPACITY_REDIS_URL"));int database=database(uri);
            String target=endpoint(uri);
            String production=System.getenv("REDIS_URL");
            if(restore && production!=null && !production.isBlank() && target.equals(endpoint(URI.create(production))))
                throw new IllegalArgumentException("Restore must not share the known production Redis endpoint");
            try(Socket socket="rediss".equals(uri.getScheme())?SSLSocketFactory.getDefault().createSocket():new Socket()){
                socket.connect(new InetSocketAddress(uri.getHost(),uri.getPort()<0?6379:uri.getPort()),5000);socket.setSoTimeout(5000);
                if(socket instanceof SSLSocket tls){var parameters=tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();}
                if(uri.getRawUserInfo()!=null){var credentials=uri.getRawUserInfo().split(":",2);
                    if(credentials.length!=2)throw new IllegalArgumentException("Missing Redis credentials");
                    String username=URLDecoder.decode(credentials[0].replace("+","%2B"),StandardCharsets.UTF_8);
                    String password=URLDecoder.decode(credentials[1].replace("+","%2B"),StandardCharsets.UTF_8);
                    if(username.isEmpty())command(socket,"AUTH",password);
                    else command(socket,"AUTH",username,password);}
                command(socket,"SELECT",Integer.toString(database));
                if(!"PONG".equals(command(socket,"PING")) || (!ping && !Long.valueOf(0).equals(command(socket,"DBSIZE"))))
                    throw new IOException("Capacity Redis is not empty");
                System.out.println(restore?"RESTORE_REDIS_EMPTY=TRUE":ping?"REDIS_READONLY_PING=OK":"CAPACITY_REDIS_EMPTY=TRUE");
            }
        }catch(Exception error){System.out.println((restore?"RESTORE_REDIS_CHECK_FAILED=":"CAPACITY_REDIS_CHECK_FAILED=")+error.getClass().getSimpleName());System.exit(1);}
    }
}
