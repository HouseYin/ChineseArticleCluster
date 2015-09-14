package net.shi.hadoop.ChineseArticleCluster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ArrayPrimitiveWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

@SuppressWarnings("deprecation")
public class NewsToVec extends Configured implements Tool {
	private static final String INPUT_PATH = "/home/galois/workspace/SougouNewsOutput/wordTFIDF";
	private static final String OUTPUT_PATH = "/home/galois/workspace/SougouNewsOutput/newsVector";
	private static final String DICT_PATH = "/home/galois/workspace/SougouNewsOutput/dictionary/part-r-00000";
	
	private static class TextDoublePairWritable 
	extends PairWritable{
		public TextDoublePairWritable(){
			super(Text.class, DoubleWritable.class);
		}
		
		public TextDoublePairWritable(Writable first, Writable second){
			super(first, second);
		}
	}
	public static class NewsToVecMapper
	extends Mapper<TextPairWritable, DoubleWritable, Text, TextDoublePairWritable>{
		public void map(TextPairWritable key, DoubleWritable value,
				Context context) throws IOException, InterruptedException{
			Text word = (Text) key.getFirst();
			Text newsID = (Text) key.getSecond();
			TextDoublePairWritable outVal = new TextDoublePairWritable(word, value);
			context.write(newsID, outVal);
		}
	}
	
	public static class NewsToVecReducer
	extends Reducer<Text, TextDoublePairWritable, Text, ArrayPrimitiveWritable>{
		private List<Text> words = new LinkedList<>();
		private ArrayPrimitiveWritable doubleArray = new ArrayPrimitiveWritable();
		
		@Override
		public void setup(Context context) throws IOException{
			//读取词汇库
			Path[] caches = DistributedCache.getLocalCacheFiles(context
					.getConfiguration());
			
			if (caches == null || caches.length <= 0) {
				System.err.println("News Number file does not exist");
				System.exit(1);
			}
			
			//此处不能直接使用java的FileReader, 例如：
			//BufferedReader in = new BufferedReader(new FileReader(caches[0].toString()));
			//因为MapReduce框架会自动给文件路径加前缀file，这样会导致FileReader不能识别路径。
			//建议使用MapReduce自带的文件读取API			
			Path newsNumPath = caches[0];
			FileSystem fs = newsNumPath.getFileSystem(context.getConfiguration());
			String line;
			BufferedReader in = new BufferedReader(new InputStreamReader(fs.open(newsNumPath)));
			try{
				while((line = in.readLine()) != null)
					words.add(new Text(line.trim()));
			}
			finally{
				in.close();
			}	
		}
		
		public void reduce(Text key, Iterable<TextDoublePairWritable> values,
				Context context) throws IOException, InterruptedException{
			Map<Text, Double> map = new HashMap<>();
			Iterator<Text> iter = words.iterator();
			while(iter.hasNext()){
				map.put(iter.next(), new Double(0.0));
			}
			
			for(TextDoublePairWritable value : values){
				DoubleWritable tfidf = (DoubleWritable) value.getSecond();
				map.replace((Text) value.getFirst(), new Double(tfidf.get()));
			}
			
			// 将Double[] 转换为 double[]
			Double[] doubles = map.values().toArray(new Double[0]);
			double[] basicDoubles = new double[doubles.length];
			for(int i = 0; i < doubles.length; ++i)
				basicDoubles[i] = (double) doubles[i];
			
			doubleArray.set(basicDoubles);
			context.write(key, doubleArray);
		}
	}

	@Override
	public int run(String[] args) throws Exception {
		// TODO Auto-generated method stub
		Configuration conf = this.getConf();
		Path dict = new Path(args[2]);
		DistributedCache.addCacheFile(dict.toUri(), conf); //放在new Job之前，否则读不到CacheFile
		
		Job job = Job.getInstance(conf, "News to Vector");
		job.setJarByClass(NewsToVec.class);
		
		job.setInputFormatClass(SequenceFileInputFormat.class);
		job.setOutputFormatClass(SequenceFileOutputFormat.class);
		
		job.setMapperClass(NewsToVecMapper.class);
		job.setReducerClass(NewsToVecReducer.class);
		
		job.setMapOutputKeyClass(Text.class);
		job.setMapOutputValueClass(TextDoublePairWritable.class);
		
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(ArrayPrimitiveWritable.class);
		
		job.setNumReduceTasks(9);
		
		FileInputFormat.addInputPath(job, new Path(args[0]));
		FileOutputFormat.setOutputPath(job, new Path(args[1]));
				
		return job.waitForCompletion(true) ? 0 : 1;
	}

	public static void main(String[] args) throws Exception{
		String[] paths = {INPUT_PATH, OUTPUT_PATH, DICT_PATH};
		
		int exitCode = ToolRunner.run(new NewsToVec(), paths);
		
		System.exit(exitCode);
	}
}
