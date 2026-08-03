using System;
using System.Threading;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;

class Program
{
    // Payload the Commute app filters on: manufacturer company id 0xFFFF, ASCII token "COMMUTE1".
    const string Token = "COMMUTE1";

    static BluetoothLEAdvertisementPublisher Build()
    {
        var pub = new BluetoothLEAdvertisementPublisher();
        var md = new BluetoothLEManufacturerData { CompanyId = 0xFFFF };
        var w = new DataWriter();
        w.WriteString(Token);
        md.Data = w.DetachBuffer();
        pub.Advertisement.ManufacturerData.Add(md);
        pub.StatusChanged += (s, e) =>
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] StatusChanged Status={e.Status} Error={e.Error}");
        return pub;
    }

    static void Main()
    {
        // Watchdog loop. A one-shot Start()+Sleep(Infinite) leaves a zombie the moment the BT
        // adapter is reset (unplug/replug, driver restart, sleep/resume): the publisher goes to
        // Aborted and never resumes on its own, so the process stays alive while nothing is on
        // air and the phone reads the office as absent. Here we watch the status and rebuild +
        // restart the publisher whenever it stops advertising, so a transient adapter hiccup
        // self-heals instead of needing a manual restart.
        var pub = Build();
        pub.Start();
        Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Started publisher for 0xFFFF '{Token}'. Watchdog active.");

        while (true)
        {
            Thread.Sleep(5000);
            var status = pub.Status;
            // Started = healthy; Waiting = still coming up. Anything else (Aborted, Stopped,
            // Created stuck) means the advertisement isn't live — rebuild from scratch, because
            // an Aborted publisher won't reliably restart in place after an adapter reset.
            if (status != BluetoothLEAdvertisementPublisherStatus.Started &&
                status != BluetoothLEAdvertisementPublisherStatus.Waiting)
            {
                Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Status={status} — advertisement not live, restarting publisher.");
                try { pub.Stop(); } catch { }
                pub = Build();
                try
                {
                    pub.Start();
                }
                catch (Exception ex)
                {
                    // Adapter may still be settling after a reset; log and retry on the next tick.
                    Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Restart failed: {ex.Message} — retrying.");
                }
            }
        }
    }
}
