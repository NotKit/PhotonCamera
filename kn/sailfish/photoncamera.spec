# Packages a tree kn/sailfish/build.sh has already staged: nothing compiles
# here, because konan cannot run inside the sb2 target.
%define __strip /bin/true
%define debug_package %{nil}
%define __provides_exclude_from ^%{_datadir}/%{name}/.*$
%define __requires_exclude ^libmaliit-glib\\.so.*$

Name:       photoncamera
Summary:    PhotonCamera, the Kotlin/Native port
Version:    %{pc_version}
Release:    1
License:    GPLv3
URL:        https://github.com/eszdman/PhotonCamera
Requires:   libhybris
Requires:   dconf

%description
PhotonCamera's own pipeline, captured through the device's Android camera2
stack by way of libhybris, in one Kotlin/Native executable.

%prep

%build

%install
mkdir -p %{buildroot}%{_datadir}/%{name}
cp -a %{_sourcedir}/stage/app/. %{buildroot}%{_datadir}/%{name}/
install -D -m 0755 %{_sourcedir}/stage/run.sh %{buildroot}%{_bindir}/%{name}
install -D -m 0644 %{_sourcedir}/stage/%{name}.desktop \
	%{buildroot}%{_datadir}/applications/%{name}.desktop
for s in 86 108 128 172; do
	install -D -m 0644 %{_sourcedir}/stage/icons/$s.png \
		%{buildroot}%{_datadir}/icons/hicolor/${s}x${s}/apps/%{name}.png
done

%files
%{_bindir}/%{name}
%{_datadir}/%{name}
%{_datadir}/applications/%{name}.desktop
%{_datadir}/icons/hicolor/*/apps/%{name}.png
