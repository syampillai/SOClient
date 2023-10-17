## Java client for SO Platform.  
  
SO platform is an object-oriented framework for developing enterprise Java applications with a PostgreSQL database
back-end. Developers typically write "data classes" (classes that represent tables in the database) and
"logic classes" (classes implementing web UI, reporting and processing logic). All such developer-defined classes are
stored in the database itself like any other business data. Logic classes can be attached to "menu options" of the
application and the logic executes when the menu option is selected by the end-user.  

## Features

This library can be used to connect to SO platform from external Java applications.
## Getting started

You may include this library in your project. Please [see here](https://jitpack.io/#syampillai/SOClient).

## Usage

```java
import com.storedobject.client.Client;
import com.storedobject.common.JSON;
import com.storedobject.common.StringList;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Test {

    public static void main(String[] args) throws IOException {
        Client client = new Client("emqim12.engravsystems.com", "emqimtest");
        String status = client.login("username", "password");
        if(status.isEmpty()) {
            System.out.println("Logged in successfully");
            Client.Data data = client.file("Weight Schedule - Approval Letter");
            if (data.error() == null) {
                print("Mime type of the file retrieved is: " + data.mimeType());
                data.stream().close();
            } else {
                print("Error retrieving file: " + data.error());
            }
            Map<String, Object> attributes = new HashMap<>();
            print("List of persons whose first name starts with the letter N");
            attributes.put("className", "core.Person");
            attributes.put("attributes", StringList.create("TitleValue AS Title", "FirstName", "DateOfBirth"));
            attributes.put("where", "FirstName LIKE 'N%'");
            printResult(client.command("list", attributes));
            print("List of usernames whose first name starts with the letter N");
            attributes.clear();
            attributes.put("className", "core.SystemUser");
            attributes.put("attributes", StringList.create(
                    "Id",
                    "Login",
                    "Person.Name AS FN",
                    "Person.DateOfBirth AS DoB",
                    "Person.MaritalStatusValue AS MaritalStatus"));
            attributes.put("where", "Person.FirstName LIKE 'N%'");
            attributes.put("order", "Person.FirstName");
            printResult(client.command("list", attributes));
            print("A person whose name starts with N (Note: The fist person found is returned)");
            attributes.clear();
            attributes.put("className", "core.Person");
            attributes.put("where", "FirstName LIKE 'N%'");
            printResult(client.command("get", attributes));
            client.logout();
        } else {
            System.err.println("Unable to login, error: " + status);
        }
    }

    private static void printResult(JSON result) {
        if("OK".equals(result.getString("status"))) {
            System.out.println(result.get("data").toPrettyString());
        } else {
            System.err.println("Error: " + result.getString("message"));
        }
    }

    private static void print(Object anything) {
        System.out.println(anything);
    }
}
```
## Additional information

SO platform wiki pages are available at [SO Platform Wiki](https://github.com/syampillai/SOTraining/wiki).
You may read the [SO Connector API](https://github.com/syampillai/SOTraining/wiki/8900.-SO-Connector-API)
documentation to understand how you can communicate and exchange data with SO platform from your programs.

If you are looking for a Datr/Flutter client, you can get it from [here](https://pub.dev/packages/so).