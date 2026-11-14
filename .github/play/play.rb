# How play.sh runs fastlane: always with review_guard.rb loaded first, so no commit can cancel a review, and
# no edit can send production and a testing track to review together.
#
#   ruby play.rb supply ARGS...
#     fastlane supply, as on the command line.
#   ruby play.rb has-bundle KEY PACKAGE TRACK[,TRACK...] CODE AAB
#     Exits 5 when any TRACK has a version code above CODE: an older tag, or a backport, which would take the
#     track back. Else exits 0 when Play has AAB itself as CODE, on the first TRACK: a re-run. Exits 4 when Play
#     has AAB as CODE, but not on the first TRACK: sent in a run whose commit Play refused. Exits 3 when Play has
#     no bundle with CODE. Stops when Play has another bundle as CODE (sent by hand, or the same tag built or
#     signed again). It only reads: the edit it opens is deleted, never committed.
#   ruby play.rb edit KEY PACKAGE [[--from TRACK] --code CODE --to TRACK[,TRACK...]] [--listing DIR]
#                     [--notes DIR] [--check-only]
#     One edit: the release CODE on --from goes on to each --to track, or with no --from, the bundle Play has as
#     CODE goes on them. It stops when a --to track has a version code above CODE. With the listing in DIR (folders by language, as play.sh makes them) and the release
#     notes in DIR/en-US/changelogs/CODE.txt. Then the edit is committed, or with --check-only only checked. supply can only promote to one track a run, so this drives
#     supply's own client and uploader in one edit.
require "digest"
require "optparse"
require_relative "review_guard"

module Crosstune
  module Play
    # supply's uploader, with the client that holds the edit in reach.
    class Edit
      def self.uploader_class
        @uploader_class ||= Class.new(Supply::Uploader) { public :client }
      end

      def initialize(options)
        Supply.config = FastlaneCore::Configuration.create(Supply::Options.available_options, options)
        @uploader = self.class.uploader_class.new
      end

      def client
        @uploader.client
      end

      # The version codes on each of the tracks named that Play has.
      def version_codes(names)
        client.tracks(*names).to_h do |track|
          [track.track, track.releases.to_a.flat_map { |release| release.version_codes.to_a }.map(&:to_i)]
        end
      end

      # "track (code)" for each of the tracks named with a version code above code: a release there would take
      # the track back to an older version.
      def newer(names, code, codes = version_codes(names))
        codes.filter_map { |name, on| "#{name} (#{on.max})" if on.any? { |c| c > code.to_i } }
      end

      # :newer when a track has a version code above code, else :absent, :on_track when Play holds this very
      # bundle as code on the first track, or :off_track when it holds it but not there. Another bundle as code
      # stops the job.
      def bundle_state(package, tracks, code, aab)
        client.begin_edit(package_name: package)
        bundles = client.client.list_edit_bundles(package, client.current_edit.id).bundles.to_a
        codes = version_codes(tracks)
        newer = newer(tracks, code, codes)
        on_track = codes.fetch(tracks.first, []).include?(code.to_i)
        client.abort_current_edit
        unless newer.empty?
          puts("Google Play has a version newer than #{code} on #{newer.join(', ')}")
          return :newer
        end

        bundle = bundles.find { |b| b.version_code.to_s == code }
        return :absent unless bundle

        unless bundle.sha256.to_s.casecmp?(Digest::SHA256.file(aab).hexdigest)
          FastlaneCore::UI.user_error!("Google Play already has another bundle as version code #{code}, and it " \
                                       "takes each version code once. Release this as a new version, with a " \
                                       "new tag.")
        end
        on_track ? :on_track : :off_track
      end

      # The bundle Play has as code, as the one release on each track, as supply's upload puts it there.
      def put(code, to)
        release = Google::Apis::AndroidpublisherV3::TrackRelease.new(
          status: Supply::ReleaseStatus::COMPLETED,
          version_codes: [code]
        )
        to.each do |name|
          track = client.tracks(name).first || Google::Apis::AndroidpublisherV3::Track.new(track: name)
          track.releases = [release]
          client.update_track(name, track)
        end
      end

      # supply's promote_track, for each track, but one that skips a release with no version codes, such as a
      # draft started in Play Console, where supply's stops with a NoMethodError.
      def promote(from, code, to)
        releases = client.tracks(from).first&.releases.to_a
        releases = releases.select { |r| r.version_codes.to_a.map(&:to_s).include?(code) }
        FastlaneCore::UI.user_error!("Track '#{from}' has no release with version code #{code}") if releases.empty?
        FastlaneCore::UI.user_error!("Track '#{from}' has #{releases.size} releases with version code #{code}") if releases.size > 1

        release = releases.first

        to.each do |name|
          promoted = release.dup
          promoted.status = Supply::ReleaseStatus::COMPLETED
          promoted.user_fraction = nil
          track = client.tracks(name).first || Google::Apis::AndroidpublisherV3::Track.new(track: name)
          # Play keeps the track's other releases as they are.
          track.releases = [promoted]
          client.update_track(name, track)
        end
      end

      # Each language's words and pictures. sync_image_upload sends only the pictures that changed.
      def send_listing(listing)
        Dir.children(listing).sort.each do |language|
          @uploader.upload_metadata(language, client.listing_for_language(language))
          @uploader.upload_images(language)
          @uploader.upload_screenshots(language)
        end
      end

      def send_notes(track)
        @uploader.perform_upload_meta([], track)
      end
    end

    module_function

    def require_supply
      require "supply"
    end

    def has_bundle(argv)
      key, package, tracks, code, aab = argv
      abort("usage: play.rb has-bundle KEY PACKAGE TRACK[,TRACK...] CODE AAB") unless aab
      require_supply
      edit = Edit.new(json_key: key, package_name: package)
      state = edit.bundle_state(package, tracks.split(","), code, aab)
      exit({ on_track: 0, off_track: 4, absent: 3, newer: 5 }.fetch(state))
    end

    def parse_edit(argv)
      key, package, *rest = argv
      options = { to: [] }
      OptionParser.new do |parser|
        parser.on("--from TRACK") { |value| options[:from] = value }
        parser.on("--code CODE") { |value| options[:code] = value }
        parser.on("--to TRACKS", Array) { |value| options[:to] = value.reject(&:empty?) }
        parser.on("--listing DIR") { |value| options[:listing] = value }
        parser.on("--notes DIR") { |value| options[:notes] = value }
        parser.on("--check-only") { options[:check_only] = true }
      end.parse!(rest)
      abort("usage: play.rb edit KEY PACKAGE ...") unless package && rest.empty?
      options.merge(key: key, package: package)
    end

    def check_edit(options)
      to = options[:to]
      abort("Name --code with --to") if !to.empty? && !options[:code]
      abort("Release notes go to one track at a time") if options[:notes] && to.size != 1
      abort("Release notes and the listing go in separate edits") if options[:notes] && options[:listing]
      abort("Nothing to send") if to.empty? && !options[:listing]
    end

    def edit(argv)
      options = parse_edit(argv)
      check_edit(options)
      require_supply
      edit = Edit.new(
        json_key: options[:key],
        package_name: options[:package],
        track: options[:from] || "internal",
        version_code: options[:code]&.to_i,
        metadata_path: options[:notes] || options[:listing],
        skip_upload_apk: true,
        skip_upload_aab: true,
        skip_upload_metadata: true,
        skip_upload_images: true,
        skip_upload_screenshots: true,
        sync_image_upload: true
      )
      edit.client.begin_edit(package_name: options[:package])
      stop_going_back(edit, options[:to], options[:code]) if options[:code]
      if options[:from]
        edit.promote(options[:from], options[:code], options[:to])
      elsif !options[:to].empty?
        edit.put(options[:code], options[:to])
      end
      edit.send_notes(options[:to].first) if options[:notes]
      edit.send_listing(options[:listing]) if options[:listing]
      if options[:check_only]
        edit.client.validate_current_edit!
        puts("Google Play would take it")
      else
        edit.client.commit_current_edit!
        puts("Sent to Google Play")
      end
    end

    def stop_going_back(edit, to, code)
      newer = edit.newer(to, code)
      return if newer.empty?

      edit.client.abort_current_edit
      FastlaneCore::UI.user_error!("Google Play already has a version newer than #{code} on #{newer.join(', ')}, so " \
                                   "nothing was sent: a release never takes a track back to an older version")
    end

    def main(argv)
      case argv.shift
      when "supply"
        argv.unshift("supply")
        load Gem.bin_path("fastlane", "fastlane")
      when "has-bundle"
        has_bundle(argv)
      when "edit"
        edit(argv)
      else
        abort("usage: play.rb supply ARGS... | has-bundle KEY PACKAGE TRACK[,TRACK...] CODE AAB | edit KEY PACKAGE ...")
      end
    end
  end
end

begin
  Crosstune::Play.main(ARGV)
rescue StandardError => e
  # Plainly, as fastlane's own command line prints its errors. Anything else is a bug here, with its stack trace.
  plain = defined?(FastlaneCore::Interface) &&
          [FastlaneCore::Interface::FastlaneError, FastlaneCore::Interface::FastlaneCommonException].any? { |c| e.is_a?(c) }
  if plain
    warn("[!] #{e.message}")
  elsif e.is_a?(Google::Apis::Error)
    warn("[!] Google Play: #{[e.status_code, e.body || e.message].compact.join(' ')}")
  else
    raise
  end
  exit(1)
end
