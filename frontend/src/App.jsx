import { useEffect, useState } from "react";

function App() {
  const [status, setStatus] = useState("Connecting to CloudShield backend...");

  useEffect(() => {
    fetch("http://localhost:8080/api/health")
        .then((response) => {
          if (!response.ok) {
            throw new Error("Backend returned an error");
          }
          return response.text();
        })
        .then((data) => {
          setStatus(data);
        })
        .catch(() => {
          setStatus("Unable to connect to CloudShield backend");
        });
  }, []);

  return (
      <div>
        <h1>CloudShield</h1>
        <p>{status}</p>
      </div>
  );
}

export default App;