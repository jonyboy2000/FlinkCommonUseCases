package org.apache.flink.quickstart.windows

import com.dataartisans.flinktraining.exercises.datastream_java.datatypes.TaxiRide
import com.dataartisans.flinktraining.exercises.datastream_java.sources.TaxiRideSource
import com.dataartisans.flinktraining.exercises.datastream_java.utils.GeoUtils
import org.apache.flink.api.scala._
import org.apache.flink.quickstart.windows.EventType.{Ended, Started}
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala.function.WindowFunction
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector

/**
  * Created by denis.shuvalov on 04/07/2018.
  *
  * The task of the “Popular Places” exercise is to identify popular places from the taxi ride data stream. This is done
  * by counting every five minutes the number of taxi rides that started and ended in the same area within the last 15 minutes.
  * Arrival and departure locations should be separately counted. Only locations with more arrivals or departures than a
  * provided popularity threshold should be forwarded to the result stream.
  *
  * The GeoUtils class provides a static method GeoUtils.mapToGridCell(float lon, float lat) which maps a location
  * (longitude, latitude) to a cell id that refers to an area of approximately 100x100 meters size. The GeoUtils class
  * also provides reverse methods to compute the longitude and latitude of the center of a grid cell.
  *
  * Please note that the program should operate in event time.
  *
  * Input Data
  * The input data of this exercise is a stream of TaxiRide events generated by the Taxi Stream Source filtered by
  * the New York City area filter of the Taxi Ride Cleansing.
  * The TaxiRideSource annotates the generated DataStream[TaxiRide] with timestamps and watermarks. Hence, there is no
  * need to provide a custom timestamp and watermark assigner in order to correctly use event time.
  *
  * Expected Output
  * The result of this exercise is a data stream of Tuple5[Float, Float, Long, Boolean, Integer] records.
  * Each record contains the longitude and latitude of the location cell (two Float values), the timestamp
  * of the count (Long), a flag indicating arrival or departure counts (Boolean), and the actual count (Integer).
  *
  * The resulting stream should be printed to standard out.
  */
object PopularPlaces {

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val taxiRidePath = "D:/Java/Learning/flinkscalaproject/src/main/resources/taxi/nycTaxiRides.gz"

    // events are out of order by max 60 seconds
    // events of 10 minutes are served in 1 second
    val rides: DataStream[TaxiRide] = env.addSource(new TaxiRideSource(taxiRidePath, 60,600))

//    val nycRides: DataStream[TaxiRide] = rides.filter{ taxiRide =>
//      if (taxiRide.isStart) GeoUtils.isInNYC(taxiRide.startLon, taxiRide.startLat)
//      else GeoUtils.isInNYC(taxiRide.endLon, taxiRide.endLat)
//    }

    val nycRides: DataStream[TaxiRide] = rides.filter { r => GeoUtils.isInNYC(r.startLon, r.startLat) && GeoUtils.isInNYC(r.endLon, r.endLat) }

    nycRides.map(GridCellRide(_))
      .keyBy { ride => (ride.gridCellId, ride.eventType) }
      .timeWindow(Time.minutes(15), Time.minutes(5))
      .apply(new PopularPlacesReporter)
      .print()

    env.execute("Calculate popular places")
  }
}

class PopularPlacesReporter extends WindowFunction[GridCellRide, Report, (Int, EventType.Value), TimeWindow] {
  val startedThresh = 5
  val endedThresh = 5

  def apply(key: (Int, EventType.Value),
            window: TimeWindow,
            elements: Iterable[GridCellRide],
            out: Collector[Report]): Unit = {

    val time = window.getEnd
    val lat = GeoUtils.getGridCellCenterLat(key._1)
    val lon = GeoUtils.getGridCellCenterLon(key._1)

    if (key._2 == EventType.Started) {
      if (elements.size > startedThresh) out.collect(Report(lon, lat, time, isArrivals = true, elements.size))
    }
    else {
      if (elements.size > endedThresh) out.collect(Report(lon, lat, time, isArrivals = false, elements.size))
    }

//    val value: EventType.Value = key._2
//    value match {
//      case Started if elements.size > 3 => out.collect(Report(lon, lat, time, isArrivals = true, elements.size))
//      case Ended if elements.size > 3 => out.collect(Report(lon, lat, time, isArrivals = false, elements.size))
//    }
  }
}

case class GridCellRide(gridCellId: Int, ride: TaxiRide, eventType: EventType.Value)
object GridCellRide {
  def apply(ride: TaxiRide): GridCellRide = {
    if (ride.isStart) new GridCellRide(GeoUtils.mapToGridCell(ride.startLon, ride.startLat), ride, Started)
    else new GridCellRide(GeoUtils.mapToGridCell(ride.endLon, ride.endLat), ride, Ended)
  }
}

object EventType extends Enumeration {
  val Started, Ended = Value
}

//Float, Float, Long, Boolean, Integer
case class Report(long: Float, lat: Float, timestamp: Long, isArrivals: Boolean, count: Int)
